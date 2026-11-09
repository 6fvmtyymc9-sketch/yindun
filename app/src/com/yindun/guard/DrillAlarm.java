package com.yindun.guard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/**
 * ============================================================================
 *  DrillAlarm —— 定期演练的闹钟
 * ----------------------------------------------------------------------------
 *  为什么用 AlarmManager 而不是 Handler/Service 里 sleep：
 *    手机一锁屏，进程随时可能被系统回收，定时器就死了。AlarmManager 是系统级
 *    的，App 被杀掉也会把广播发出来。
 *
 *  为什么用 setAndAllowWhileIdle：
 *    国产 ROM（华为 EMUI/鸿蒙）在深度休眠下会推迟普通闹钟，doze 模式里
 *    只有 setAndAllowWhileIdle / setExactAndAllowWhileIdle 能准点响。
 *
 *  为什么每次 onResume 都重排一次：
 *    国产 ROM 常年清后台闹钟，"设一次管一辈子"在这里不成立。
 *    每次打开 App 重设一遍，是最省事也最可靠的做法。
 * ============================================================================
 */
class DrillAlarm {

    private static final String TAG = "DrillAlarm";
    static final int REQ = 9527;                 // PendingIntent 请求码，固定值才能覆盖旧闹钟
    static final int WEEKDAY = Calendar.MONDAY;  // 周一
    static final int HOUR = 10;                  // 上午 10 点

    /** 排下一次演练提醒（幂等：重复调用只会覆盖，不会叠加） */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        long at = nextTriggerAt();
        PendingIntent pi = pending(c);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
            Log.i(TAG, "下次演练提醒已排：" + new java.util.Date(at));
        } catch (Exception e) {
            Log.w(TAG, "排闹钟失败：" + e.getMessage());
        }
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c));
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, DrillReceiver.class);
        i.setAction("com.yindun.guard.DRILL_REMIND");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ, i, flags);
    }

    /** 下一个「周一 10:00」（今天已过就推到下周） */
    static long nextTriggerAt() {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, HOUR);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        // 先对齐到本周的目标星期
        int diff = (WEEKDAY - now.get(Calendar.DAY_OF_WEEK) + 7) % 7;
        t.add(Calendar.DAY_OF_YEAR, diff);
        if (t.getTimeInMillis() <= now.getTimeInMillis()) {
            t.add(Calendar.DAY_OF_YEAR, 7);
        }
        return t.getTimeInMillis();
    }
}
