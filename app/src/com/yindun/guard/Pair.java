package com.yindun.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.HttpURLConnection;
import java.security.SecureRandom;

/**
 * Pair —— 老人端在云端的身份
 * ============================================================
 * 原来所有设备都用 elderId=demo，任何人订阅 demo 都能看到这位老人的全部预警。
 * 现在每台老人手机第一次运行时生成：
 *   elderId：10 位小写字母数字（云端用它区分不同老人）
 *   key    ：32 位随机密钥（只存在本机；云端只存哈希）
 * 所有发往云端的请求都带上 X-Yd-Elder / X-Yd-Key，云端校验通过才收。
 * 子女端通过 6 位配对码绑定这个 elderId（见 cloud/src/Pairs.js）。
 *
 * 【演示模式】模拟器默认使用公开编号 demo，不带密钥 ——
 * 这样投影上的网页子女端（订阅 demo）照常能看到演示数据。真机一律用独立编号。
 * ============================================================
 */
public class Pair {

    private static final String SP = "yindun";
    public static final String DEMO = "demo";

    /** 当前使用的老人编号 */
    public static String id(Context c) {
        if (demo(c)) return DEMO;
        ensure(c);
        return sp(c).getString("elder_id", DEMO);
    }

    /** 是否处于演示模式（模拟器默认开，真机默认关；设置里可切换） */
    public static boolean demo(Context c) {
        return sp(c).getBoolean("demo_mode", Guard.isEmulator());
    }

    public static void setDemo(Context c, boolean on) {
        sp(c).edit().putBoolean("demo_mode", on).apply();
    }

    /**
     * 她到底开通过守护没有。
     *
     * 【为什么不能用"装了就自启"】装了只看一眼就走的人不少，对她们来说
     * 每次开机常驻一条通知跟流氓软件一样。所以只有真正拉起过守护服务的人，
     * 才配得上开机自启。标记由 MainActivity.startGuard() 写。
     */
    public static boolean enabled(Context c) {
        return sp(c).getBoolean("guard_enabled", false);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("guard_enabled", on).apply();
    }

    /** 给一个 HTTP 请求带上身份头（演示模式不带） */
    public static void sign(Context c, HttpURLConnection conn) {
        if (demo(c)) return;
        ensure(c);
        conn.setRequestProperty("X-Yd-Elder", sp(c).getString("elder_id", ""));
        conn.setRequestProperty("X-Yd-Key", sp(c).getString("elder_key", ""));
    }

    private static synchronized void ensure(Context c) {
        SharedPreferences s = sp(c);
        if (s.getString("elder_id", "").length() >= 8 && s.getString("elder_key", "").length() >= 16) return;
        SecureRandom r = new SecureRandom();
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 10; i++) id.append(abc.charAt(r.nextInt(abc.length())));
        byte[] k = new byte[16];
        r.nextBytes(k);
        StringBuilder key = new StringBuilder();
        for (byte b : k) key.append(String.format("%02x", b));
        s.edit().putString("elder_id", id.toString()).putString("elder_key", key.toString()).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
