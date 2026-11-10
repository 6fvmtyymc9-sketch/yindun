package com.yindun.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Stats —— 首页上的几个数字：今天检查了多少、守护了几天、最近一次提醒
 * ============================================================
 * 首页要像一个"一直在工作"的产品，就得拿出真实的数字，而不是写死一句"我在看着"。
 * 这里只记计数，不记内容：短信几条、来电几通、提醒几次，按天清零。
 *
 * 最近一次提醒单独存一份，首页每次打开都能读到（原来只靠实时推送，
 * App 被杀掉重开后"最近一次提醒"就丢了）。
 * ============================================================
 */
public class Stats {

    private static final String SP = "yindun_stats";
    private static Context app;

    /** 守护服务启动时调一次，让没有 Context 的地方（EventBus）也能记数 */
    public static void init(Context c) {
        app = c.getApplicationContext();
    }

    public static void alert(String level, String why) {
        if (app != null) alert(app, level, why);
    }

    public static void sms(Context c)   { bump(c, "sms"); }
    public static void call(Context c)  { bump(c, "call"); }
    public static void wechat(Context c) { bump(c, "wx"); }

    /** 记一次提醒（watch 级只是记一笔，不算"提醒"） */
    public static void alert(Context c, String level, String why) {
        if (!"warn".equals(level) && !"high".equals(level) && !"block".equals(level)) return;
        bump(c, "alert");
        sp(c).edit()
                .putString("last_level", level)
                .putString("last_why", why == null ? "" : why)
                .putLong("last_at", System.currentTimeMillis())
                .apply();
    }

    /** 给首页的 JSON */
    public static String json(Context c) {
        try {
            rollDay(c);
            SharedPreferences s = sp(c);
            JSONObject o = new JSONObject();
            o.put("days", days(c));
            o.put("sms", s.getInt("sms", 0));
            o.put("call", s.getInt("call", 0));
            o.put("wx", s.getInt("wx", 0));
            o.put("alert", s.getInt("alert", 0));
            long at = s.getLong("last_at", 0);
            if (at > 0) {
                JSONObject last = new JSONObject();
                last.put("level", s.getString("last_level", ""));
                last.put("why", s.getString("last_why", ""));
                last.put("time", new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(at)));
                last.put("mins", (System.currentTimeMillis() - at) / 60000L);
                last.put("today", today().equals(new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date(at))));
                o.put("last", last);
            }
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------

    private static synchronized void bump(Context c, String key) {
        rollDay(c);
        SharedPreferences s = sp(c);
        s.edit().putInt(key, s.getInt(key, 0) + 1).apply();
    }

    /** 过了零点，今天的计数清零 */
    private static synchronized void rollDay(Context c) {
        SharedPreferences s = sp(c);
        String d = today();
        if (!d.equals(s.getString("day", ""))) {
            s.edit().putString("day", d).putInt("sms", 0).putInt("call", 0)
                    .putInt("wx", 0).putInt("alert", 0).apply();
        }
    }

    /** 从安装那天算起守护了几天（含当天） */
    private static long days(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            long d = (System.currentTimeMillis() - pi.firstInstallTime) / 86400000L;
            return Math.max(1, d + 1);
        } catch (Exception e) {
            return 1;
        }
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
