package com.yindun.guard;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.provider.ContactsContract;

/**
 * NumberInfo —— 回答一个问题：这个号码是谁？
 * ============================================================
 * 【为什么单独抽一个类】
 * 短信要用（这条短信是不是陌生人发的），通话也要用（这通电话是不是陌生人打的）。
 * 两处逻辑一模一样，写两份将来改一处忘一处，就会出"短信认得出、电话认不出"的怪事。
 *
 * 【三种身份，不是两种】
 *   contact  = 通讯录里的人（家人、朋友）→ 一律不报警
 *   service  = 服务号（95588、10086、106 开头）→ 天天发短信，不报警
 *   stranger = 陌生人 → 这才是我们要盯的
 *
 * 【为什么要把 service 单独分出来】
 * 银行、快递、运营商天天发短信。如果都当陌生人，子女一天收十条通知，
 * 第三天就把通知关了。到时候真正那条"妈妈可能被骗了"，她也看不见。
 *
 * 预警系统不是死于漏报，是死于"狼来了"。
 * ============================================================
 */
public class NumberInfo {

    public static final String CONTACT  = "contact";
    public static final String SERVICE  = "service";
    public static final String STRANGER = "stranger";

    /** 号码归一化：去掉 +86、空格、横线、括号，方便拿去查通讯录 */
    public static String normalize(String n) {
        if (n == null) return "";
        String s = n.replace(" ", "").replace("-", "")
                    .replace("(", "").replace(")", "");
        if (s.startsWith("+86")) s = s.substring(3);
        else if (s.startsWith("86") && s.length() > 11) s = s.substring(2);
        return s;
    }

    /**
     * 判断号码身份。
     *
     * 【没权限时怎么办】
     * 查不了通讯录就当陌生人。宁可多报一条，不能漏报一条——
     * 多报的代价是子女多看一眼手机，漏报的代价是钱没了。
     */
    public static String classify(Context ctx, String rawNumber) {
        String num = normalize(rawNumber);
        if (num.length() == 0) return STRANGER;

        // 服务号：短号、95 开头（银行）、106 开头（行业短信）
        if (num.length() <= 6 || num.startsWith("95") || num.startsWith("106")) {
            return SERVICE;
        }

        // 查通讯录
        try {
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                            .appendPath(num).build(),
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                boolean found = c.moveToFirst();
                c.close();
                if (found) return CONTACT;
            }
        } catch (Exception ignored) {
            // 权限没开 / 系统限制，走下面按陌生人算
        }
        return STRANGER;
    }

    /** 通话记录里查到的一条 */
    public static class Call {
        public String number;
        public int type;      // 1=呼入 2=呼出 3=未接 5=拒接
        public long date;     // 毫秒时间戳
    }

    /**
     * 查最近一条通话记录。
     *
     * 【为什么需要它】
     * 安卓 9 以后，来电号码在很多机型上不再直接给第三方（onCallStateChanged 里
     * 传进来的 number 是 null）。拿不到号码，就判不出是不是陌生人。
     *
     * 兜底办法：通话一接通，立刻去通话记录里捞最新那一条。
     * 只认 60 秒以内的，避免把昨天那条旧记录当成这通电话。
     */
    public static Call latestCall(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                    null, null, CallLog.Calls.DATE + " DESC");
            if (c != null) {
                if (c.moveToFirst()) {
                    Call r = new Call();
                    r.number = c.getString(0);
                    r.type = c.getInt(1);
                    r.date = c.getLong(2);
                    c.close();
                    // 超过 60 秒的记录不算"这通电话"
                    if (System.currentTimeMillis() - r.date < 60000) return r;
                    return null;
                }
                c.close();
            }
        } catch (Exception ignored) {
            // 没给通话记录权限
        }
        return null;
    }

    /**
     * 通话记录里有没有"家人打来但被拒接"的那一条。
     *
     * 【这条判据为什么重要】
     * 骗子最常说的一句话是"别接其他人电话，别人都是骗子"。
     * 老人拒接女儿的电话，是这个骗局最真实的动作痕迹——而且它绕不过去。
     *
     * 【为什么用通话记录而不是来电事件】
     * 通话中再来一个电话，安卓给的信号很不稳定（有的机型根本不通知第三方）。
     * 但不管拒接还是未接，通话记录里一定会留下一条。这条跑不掉。
     */
    public static boolean hasRejectedFamilyCall(Context ctx, long sinceMillis) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                    CallLog.Calls.DATE + " > ?",
                    new String[]{String.valueOf(sinceMillis)},
                    CallLog.Calls.DATE + " DESC");
            if (c != null) {
                boolean hit = false;
                while (c.moveToNext()) {
                    String num = c.getString(0);
                    int type = c.getInt(1);
                    // 3=未接 5=拒接
                    if (type != 3 && type != 5) continue;
                    if (CONTACT.equals(classify(ctx, num))) { hit = true; break; }
                }
                c.close();
                return hit;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
