package com.yindun.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * ============================================================================
 *  DrillPending —— "上次那通电话的套路，还没陪她练过"
 * ----------------------------------------------------------------------------
 *  功能④ 的最后一环：通话复盘判为诈骗 → 下次演练提醒直接说这件事，
 *  而不是每周那句不痛不痒的"该做一次反诈演练了"。
 *
 *  为什么只记套路名，不记原话：
 *    原话里有对方的姓名、卡号、金额。演练页本来就要重新编台词，
 *    记住"冒充公检法"这一层就够用了，多存一个字都是多余的隐私风险。
 *    云端会更进一步：CaseBank 用 type 生成骨架，再换外壳重写台词。
 *
 *  为什么要过期：
 *    两周后再弹"上次那通电话"，老人早就想不起来是哪通了。
 *    超过 3 天就不再用这条文案，退回通用提醒 —— 宁可少一点针对性，
 *    也不能让她觉得这个 App 在说胡话。
 *
 *  为什么用完就清：
 *    同一次复盘反复提醒会变成骚扰。点开局就清，下一通诈骗再记新的。
 * ============================================================================
 */
class DrillPending {

    private static final String TAG = "DrillPending";
    private static final String SP = "yindun_drill_pending";

    /** 超过这个时间就不再提"上次那通电话" */
    private static final long TTL_MS = 3L * 24 * 3600 * 1000;

    /** 复盘判为 high 及以上时记一笔 */
    static void remember(Context c, String type, int stage) {
        if (type == null || type.trim().length() == 0) type = "疑似诈骗";
        sp(c).edit()
                .putString("type", type.trim())
                .putInt("stage", stage)
                .putLong("at", System.currentTimeMillis())
                .apply();
        Log.i(TAG, "记下待演练套路：" + type + " S" + stage);
    }

    /** 有没有还新鲜的、值得单独提一句的案例 */
    static boolean fresh(Context c) {
        long at = sp(c).getLong("at", 0);
        return at > 0 && System.currentTimeMillis() - at < TTL_MS;
    }

    /** 诈骗类型，例如"冒充公检法"；没有则返回空串 */
    static String type(Context c) {
        return sp(c).getString("type", "");
    }

    static int stage(Context c) {
        return sp(c).getInt("stage", 0);
    }

    /** 已经用它开过局了，清掉；否则每周都会提同一件事 */
    static void clear(Context c) {
        sp(c).edit().clear().apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
