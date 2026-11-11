package com.yindun.guard;

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

    private static volatile String id = null;
    private static volatile String number = null;
    private static volatile long beganAt = 0;

    /**
     * 开一场新通话。由 Guard.startNewCall() 调用。
     *
     * @param number 对方号码，可能为 null（系统不给）
     * @return 本次会话 id
     */
    static synchronized String begin(String number) {
        id = newId();
        CallSession.number = number;
        beganAt = System.currentTimeMillis();
        return id;
    }

    /**
     * 当前这场通话的 id；没有或已过期返回 null。
     *
     * 【为什么过期返回 null 而不是清空字段】
     * 调用方（Notifier）拿到 null 只是"这次不带 id"，
     * 下一次 begin() 会整个换掉。不需要额外的清理线程。
     */
    static String id() {
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
     */
    static synchronized String reuseOrBegin(String number) {
        if (id() != null && number != null && number.equals(CallSession.number)) {
            return id;
        }
        return begin(number);
    }

    /** 当前这场通话的对方号码，同样受有效期约束 */
    static String number() {
        if (id() == null) return null;
        return number;
    }

    /** 给日志用：还剩多少分钟有效期 */
    static long ageSec() {
        if (id == null) return -1;
        return (System.currentTimeMillis() - beganAt) / 1000;
    }

    /**
     * 生成 id：c + 36 进制时间戳 + 3 位 36 进制随机。
     *
     * 为什么不用 UUID：埋点日志里一行几十个字符读不动。
     * 36 进制时间戳 8~9 位 + 随机 3 位，同一台手机、同一毫秒也不会撞，
     * 而且排序即时间顺序，排查时一眼能看出先后。
     */
    private static String newId() {
        String r = Integer.toString((int) (Math.random() * 46656), 36);
        while (r.length() < 3) r = "0" + r;    // 补齐三位，长度固定才好肉眼比对
        return "c" + Long.toString(System.currentTimeMillis(), 36) + r;
    }
}
