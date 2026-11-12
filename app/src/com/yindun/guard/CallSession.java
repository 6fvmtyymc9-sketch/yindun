package com.yindun.guard;

import java.security.SecureRandom;

/**
 * ============================================================================
 *  CallSession —— 一通电话的身份
 * ----------------------------------------------------------------------------
 *  为什么需要这个东西：
 *    子女端要看的不是"妈妈收到了 6 条互不相干的预警"，而是"这一通电话里
 *    到底发生了什么"：陌生号响铃 → 接通 → 聊满 5 分钟复问 → 通话中收到验证码
 *    → 挂断后打开手机银行 → 红屏拦住 → 自动打给女儿。
 *    这 7 件事发生在 20 分钟内，靠时间戳猜归组不可靠（两条短信也可能插进来）。
 *    所以每次通话开工时生成一个 id，这一场里的每条上报都带上它，云端按 id 归组。
 *
 *  为什么通话结束后不清空这个 id：
 *    最要命的一步"挂断之后才去转账"发生在通话结束以后。骗子常常说
 *    "您先挂了，去银行 APP 里操作"，所以 CALL_STATE_IDLE 之后仍然要能归组。
 *    这里用"有效期"而不是"结束即清"：超过 30 分钟就不再带，避免把两小时后
 *    一条完全无关的预警错挂到上午那通电话上。
 *
 *  为什么下一通电话来了要换新 id：
 *    呼叫等待时老人可能中途接起另一个号码，那是一通新的电话，
 *    混在上一场里会让子女端看到"一次事件里出现了两个不同号码"。
 *
 *  线程安全：Guard 由系统回调（电话状态广播）驱动，CallWatcher 每 5 秒 tick，
 *  SmsReceiver 在另一条线程里，都可能同时读写。用 synchronized 加 volatile，
 *  代价可以忽略——这些方法一秒最多被调几次。
 * ============================================================================
 */
class CallSession {

    /** 一次事件的有效归组窗口：30 分钟。见类注释"为什么通话结束后不清空" */
    private static final long TTL_MS = 30L * 60 * 1000;

    /** 这一场目前处在哪个阶段 */
    private static final int RINGING = 0;
    private static final int OFFHOOK = 1;
    /** 已挂断。id 还留着（挂断后去转账才是骗子真正要的那一步），但不再接受空号码续接 */
    private static final int IDLE = 2;

    private static volatile String id = null;
    private static volatile String number = null;
    private static volatile long beganAt = 0;
    private static volatile int phase = IDLE;

    /** 生成 callId 的随机源。见 newId() 注释：Math.random 的序列可预测 */
    private static final SecureRandom RND = new SecureRandom();

    /**
     * 开一场新通话。由 Guard.startNewCall() 调用。
     *
     * @param number 对方号码，可能为 null（系统不给）
     * @return 本次会话 id
     */
    static synchronized String begin(String number) {
        id = newId();
        // 空串一律存成 null：系统有时送 ""，直接存进去会让"号码是否相同"永远判不中
        CallSession.number = blank(number) ? null : number;
        beganAt = System.currentTimeMillis();
        phase = RINGING;
        return id;
    }

    /**
     * 当前这场通话的 id；没有或已过期返回 null。
     *
     * 【为什么过期返回 null 而不是清空字段】
     * 调用方（Notifier）拿到 null 只是"这次不带 id"，
     * 下一次 begin() 会整个换掉。不需要额外的清理线程。
     */
    static synchronized String id() {
        String v = id;
        if (v == null) return null;
        if (System.currentTimeMillis() - beganAt > TTL_MS) return null;
        return v;
    }

    /**
     * 响铃时已经被 begin 过，接通时不要另开一场。
     *
     * 【为什么接通必须复用而不是新建】
     * 时间轴的第一格是"陌生号码正在响铃"，那一格在界面上显示时还没有 callId
     * 可带——如果接通时换一个 id，响铃那条就永远孤立在时间轴外面，
     * 子女端看到的第一个事件是"接通"，恰好丢掉了最重要的"是陌生号打来的"。
     *
     * 【号码为空时也必须复用】
     * 系统在接通那一瞬间常常还没把号码送过来（实测日志里出现过
     * `通话开始 who=stranger num=` 后面是空的）。原来这里要求"号码相同才复用"，
     * 于是空号码直接新建一场，等真实号码到了再新建一场——一次通话被劈成两个 id，
     * 时间轴断成两截。号码拿不到时无法判断是不是另一通电话，只能沿用当前的；
     * 号码后到就补上。
     */
    static synchronized String reuseOrBegin(String number) {
        // 【空串和 null 一样要当"不知道号码"】实测系统送来的不是 null 而是 ""：
        //   日志里出现过 `来号=13900009999 存号=` —— 存的是空串，
        //   于是 equals 判不中，一次通话被劈成两个 id。必须用 blank 判，不能只判 null。
        boolean blankNum = blank(number);

        // 【2026-11-11 修·码道审查第 3 条】挂断之后又来了一通、但号码没拿到：
        //   空号码的意思是"不知道是谁"，不是"和谁都一样"。
        //   原来这种情况会沿用上一场（30 分钟内一直有效），下一通电话的内容就被
        //   混进上一场的时间轴里 —— 子女端会看到"一次事件里出现了两个号码"，
        //   这与类注释里"下一通电话来了要换新 id"的设计直接矛盾。
        //   已挂断又拿不到号码，没有任何办法证明是同一通，只能开新场。
        if (phase == IDLE && blankNum) return beginOffhook(number);

        boolean reuse = id() != null && (blankNum || blank(CallSession.number)
                || number.equals(CallSession.number));
        if (reuse) {
            if (!blankNum) CallSession.number = number;   // 补上后到的号码
            phase = OFFHOOK;
            return id;
        }
        return beginOffhook(number);
    }

    /**
     * 开一场新通话并立刻标记为"已接通"。
     *
     * 能走到这里的分两种情况：接通时号码和上一场对不上（换了一通电话），
     * 或者已挂断后又拿不到号码。两种都是"新的一场"，但已经不是在响铃阶段了。
     */
    private static String beginOffhook(String number) {
        String nid = begin(number);
        phase = OFFHOOK;
        return nid;
    }

    /**
     * 通话挂断。由 Guard 的挂断分支调用。
     *
     * 【只改状态，不清 id】这是整个类最要紧的一条：骗子最爱说"您先挂了，
     * 去银行 App 里操作"，真正转账那一步发生在挂断之后。清掉 id 就等于
     * 把时间轴最关键的最后一格切掉了。
     * 所以挂断只是"不再接受空号码续接"，id 本身仍然对银行、短信等上报生效，
     * 直到 30 分钟有效期自然到期。
     */
    static synchronized void end() {
        if (id == null) return;
        phase = IDLE;
        // 这条日志是给排查用的：事后若发现"两条不相干的事进了同一条时间轴"，
        // 先看这里有没有"已挂断"——有的话说明问题出在别处，没有就是这个方法没被调到。
        android.util.Log.i("YinDun", "会话=" + id + " 已挂断，id 保留 "
                + (TTL_MS / 60000) + " 分钟（挂断后去转账才是骗子真正要的那一步）");
    }

    private static boolean blank(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** 当前这场通话的对方号码，同样受有效期约束 */
    static String number() {
        if (id() == null) return null;
        return number;
    }

    /**
     * 现在是不是正在通电话（响铃中或已接通）。
     *
     * 【为什么不能用 id() != null 代替】
     *   id 在挂断后还要留 30 分钟给"挂断后去转账"那一步归组用，
     *   所以 id 非空只说明"最近有一通电话"，不说明电话还没挂。
     *   两件事必须分开问，混着用的后果是：挂断十分钟后仍然以为在通话中，
     *   该拨的号一直不拨。
     */
    static synchronized boolean inCall() {
        return id != null && phase != IDLE;
    }

    /** 给日志用：还剩多少分钟有效期 */
    static long ageSec() {
        if (id == null) return -1;
        return (System.currentTimeMillis() - beganAt) / 1000;
    }

    /**
     * 生成 id：c + 36 进制时间戳 + 6 位 36 进制随机。
     *
     * 为什么不用 UUID：埋点日志里一行几十个字符读不动。
     * 36 进制时间戳 8~9 位 + 随机 6 位，同一台手机、同一毫秒也不会撞，
     * 而且前缀排序即时间顺序，排查时一眼能看出先后。
     *
     * 【随机段为什么是 6 位】（2026-11-11 码道审查第 1 条）
     *   原来是 3 位：36^3 = 46656 种。而时间戳部分是可猜的 —— 攻击者只要大致
     *   知道事情发生在哪几分钟，枚举几万次就能撞上别人家的 callId。
     *   6 位是 36^6 ≈ 21.7 亿种，撞不动了。
     *   用 SecureRandom 而不是 Math.random：后者是线性同余，序列可预测。
     *   但这只是"加固"，不是防线本身 —— 真正的兜底在服务端：
     *   callId 必须连同凭证一起用，且只能查到凭证所属的那一位老人。
     */
    private static String newId() {
        long v = RND.nextLong() & Long.MAX_VALUE;          // 取非负，避免负数带出 "-"
        String r = Long.toString(v % 2176782336L, 36);     // 2176782336 = 36^6
        while (r.length() < 6) r = "0" + r;                // 补齐六位，长度固定才好比对
        return "c" + Long.toString(System.currentTimeMillis(), 36) + r;
    }
}
