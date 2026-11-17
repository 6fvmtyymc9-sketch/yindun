package com.yindun.guard;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 同一号码连发短信的累计判定。
 *
 * <pre>
 * ============================================================
 *  为什么需要这个类（2026-11-17）
 * ============================================================
 * 实测：老人在真机上收到三条短信 —— 「我是警察」「赚钱」「转钱」。
 * 单条分别得 65 / 15 / 15 分，只有第一条过 warning 线。
 * 可这三条连起来是一套完整的诈骗剧本：亮身份 → 抛诱饵 → 催转账。
 *
 * 系统按条独立判分，剧本被切碎了。骗子只要把话拆成几条发，
 * 每条都能压到报警线以下 —— 这不是猜想，是实测撞出来的。
 *
 * 【为什么不干脆调低阈值】
 * 阈值一降，正常短信就开始误报。「快递已到」「验证码 1234」
 * 这种一天好几条，老人一旦觉得「这玩意儿老瞎叫」，
 * 就会去关权限 —— 比漏报更致命。
 *
 * 【累计只在同一号码上做】
 * 换号码发的不累计。理由：诈骗剧本几乎总是同一个号码连着发
 * （骗子也在意成本，不会为三条短信换三个号），
 * 而不同号码的多条短信在生活里太常见（快递、银行、同事）。
 *
 * 【为什么不用内存变量】
 * 短信广播来时进程可能是被系统刚刚拉起的，判完又可能被回收。
 * 内存里的计数撑不过两条短信之间的那段空闲，必须落盘。
 * ============================================================
 * </pre>
 */
public class RepeatSms {

    private static final String TAG = "YinDun";

    private static final String PREF = "yd_repeat_sms";
    private static final String K = "n:";

    /** 累计窗口：10 分钟。诈骗剧本不会慢慢发，隔天再发的各算各的。 */
    private static final long WINDOW_MS = 10 * 60 * 1000L;

    /** 第几条开始算「连发」。第 2 条不算 —— 同事先发文字再发图片，太常见。 */
    private static final int MIN_BURST = 3;

    /** 连发之后每多一条，累计分再加多少 */
    private static final int PER_EXTRA = 15;

    /** 最多记多少个号码，超了删最旧的。老人手机不会同时被 24 个号轰炸。 */
    private static final int MAX_RECORDS = 24;

    /**
     * 判重窗口：同一条短信被重复广播。
     * MTK 平台和 EMUI 上见过同一条短信触发两次 onReceive，
     * 双卡手机也可能两张卡各报一次。不挡住的话计数会凭空翻倍。
     */
    private static final long DUP_MS = 5000L;

    /** 累计结果 */
    public static class Verdict {
        /** 窗口内这个号码一共发了几条（含本条） */
        public int count = 1;
        /** 各条「实质分」之和。实质分 = 单条总分扣掉陌生号码那个 +15，避免重复白送 */
        public int sum = 0;
        /** 连发加成 */
        public int bonus = 0;
        /** 各条里最高的阶段序号 0..4，取最高就是「骗子走到的最远那一步」 */
        public int topRank = 0;
        /** 是否构成连发（>= MIN_BURST 条） */
        public boolean burst = false;

        /** 合并后这一组短信的分数。base 是号码身份分（陌生 +15） */
        public int pooled(int base) {
            return base + sum + bonus;
        }
    }

    /**
     * 记下这一条，返回这个号码在窗口内的累计情况。
     *
     * @param from        发件号码，空串表示系统/推送类无号码短信（不参与累计）
     * @param substantive 这一条的实质分（已扣掉号码身份分）
     * @param rank        这一条的阶段序号（S0=0 … S4=4）
     * @param body        正文，只用来判重
     * @param now         到达时间
     */
    public static synchronized Verdict record(Context ctx, String from,
                                              int substantive, int rank,
                                              String body, long now) {
        Verdict v = new Verdict();
        v.sum = Math.max(0, substantive);
        v.topRank = clampRank(rank);

        // 没有发件号码就不累计：系统推送、运营商通知都长这样，
        // 把它们混在一个桶里，攒到三条就能凭空判成诈骗。
        if (from == null || from.length() == 0) return v;

        final int hash = (body == null) ? 0 : body.hashCode();
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);

        String key = K + norm(from);
        String raw = sp.getString(key, null);

        int count = 0, sum = 0, topRank = 0, lastHash = 0;
        long first = now, last = 0;

        if (raw != null) {
            String[] p = raw.split(",");
            if (p.length >= 6) {
                try {
                    first = Long.parseLong(p[0]);
                    last = Long.parseLong(p[1]);
                    count = Integer.parseInt(p[2]);
                    sum = Integer.parseInt(p[3]);
                    topRank = Integer.parseInt(p[4]);
                    lastHash = Integer.parseInt(p[5]);
                } catch (NumberFormatException e) {
                    count = 0;
                    sum = 0;
                    topRank = 0;
                }
            }
        }

        // 窗口过期，或者系统时间被往回调过，都从头算
        boolean expired = last > 0 && (now - last > WINDOW_MS || now < last);
        if (expired) {
            count = 0;
            sum = 0;
            topRank = 0;
            lastHash = 0;
            first = now;
        }

        // 同一条短信重复广播：不计数、不改分，原样返回
        if (count > 0 && last > 0 && (now - last) < DUP_MS && hash == lastHash) {
            v.count = count;
            v.sum = sum;
            v.topRank = topRank;
            v.bonus = bonusOf(count);
            v.burst = count >= MIN_BURST;
            return v;
        }

        count += 1;
        sum += Math.max(0, substantive);
        if (clampRank(rank) > topRank) topRank = clampRank(rank);

        v.count = count;
        v.sum = sum;
        v.topRank = topRank;
        v.bonus = bonusOf(count);
        v.burst = count >= MIN_BURST;

        sp.edit()
                .putString(key, first + "," + now + "," + count + "," + sum + "," + topRank + "," + hash)
                .apply();

        prune(sp, key, now);

        android.util.Log.i(TAG, "短信累计 " + norm(from) + " 第 " + count + " 条，"
                + "实质分和=" + sum + " 加成=" + v.bonus + " 构成连发=" + v.burst);
        return v;
    }

    /** 这个号码窗口内的累计现状，不发新短信时查（调试/看板用） */
    public static synchronized Verdict peek(Context ctx, String from, long now) {
        Verdict v = new Verdict();
        if (from == null || from.length() == 0) return v;
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = sp.getString(K + norm(from), null);
        if (raw == null) return v;
        String[] p = raw.split(",");
        if (p.length < 6) return v;
        try {
            long last = Long.parseLong(p[1]);
            if (now - last > WINDOW_MS) return v;
            v.count = Integer.parseInt(p[2]);
            v.sum = Integer.parseInt(p[3]);
            v.topRank = Integer.parseInt(p[4]);
            v.bonus = bonusOf(v.count);
            v.burst = v.count >= MIN_BURST;
        } catch (NumberFormatException ignored) {
        }
        return v;
    }

    /** 清掉这个号码的记录。用户点「认识」之后调用，别让旧账一直压着。 */
    public static synchronized void forget(Context ctx, String from) {
        if (from == null || from.length() == 0) return;
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(K + norm(from)).apply();
    }

    public static synchronized void clear(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ------------------------------------------------------------

    /**
     * 连发加成：从第 3 条起每条 +15，但封顶。
     *
     * 【2026-11-17 加封顶，码道审查第 9 条】
     * 原来是纯线性：(count - 3 + 1) * 15，发到第 8 条就 +90。
     * 一个陌生人（比如刚加上的快递员、房东、装修工）连发八条正常消息，
     * 每条只要沾一点"急""快""帮忙"这类弱词，累计就能被推到 block —— 盖屏。
     * 条数多不等于危险，危险的是分数高。
     *
     * 封顶取 60（相当于最多认四条额外加成）：三条起算的剧本已经足够露馅，
     * 再往上加只是把"话多"当成"可疑"。超过 60 之后，分数只由各条实质分决定。
     */
    private static final int BONUS_CAP = 60;

    private static int bonusOf(int count) {
        if (count < MIN_BURST) return 0;
        int b = (count - MIN_BURST + 1) * PER_EXTRA;
        return Math.min(b, BONUS_CAP);
    }

    private static int clampRank(int r) {
        if (r < 0) return 0;
        return Math.min(r, 4);
    }

    /** 号码归一化：去掉空格、短横、+86 前缀，避免同一个号被记成两条 */
    private static String norm(String phone) {
        String s = phone.replaceAll("[\\s\\-()]", "");
        if (s.startsWith("+86")) s = s.substring(3);
        else if (s.startsWith("0086")) s = s.substring(4);
        else if (s.startsWith("86") && s.length() > 11) s = s.substring(2);
        return s;
    }

    /** 删过期记录；还超量就删最旧的。 */
    private static void prune(SharedPreferences sp, String keepKey, long now) {
        java.util.Map<String, ?> all = sp.getAll();
        SharedPreferences.Editor ed = null;
        int alive = 0;
        String oldestKey = null;
        long oldestTs = Long.MAX_VALUE;

        for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(K)) continue;
            Object val = e.getValue();
            if (!(val instanceof String)) continue;
            String[] p = ((String) val).split(",");
            long last = 0;
            try {
                if (p.length >= 2) last = Long.parseLong(p[1]);
            } catch (NumberFormatException ignored) {
            }
            boolean expired = last > 0 && (now - last > WINDOW_MS);
            if (expired) {
                if (ed == null) ed = sp.edit();
                ed.remove(k);
                continue;
            }
            alive++;
            if (last < oldestTs) {
                oldestTs = last;
                oldestKey = k;
            }
        }

        // 超量：删最旧的那条，但别把刚写进去的这条删了
        if (alive > MAX_RECORDS && oldestKey != null && !oldestKey.equals(keepKey)) {
            if (ed == null) ed = sp.edit();
            ed.remove(oldestKey);
        }
        if (ed != null) ed.apply();
    }
}
