package com.yindun.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * ============================================================================
 *  DrillReceiver —— 到点了，提醒老人做反诈演练
 * ----------------------------------------------------------------------------
 *  一条通知，不弹窗、不响铃轰炸。演练是"疫苗"，不是警报：
 *  老人正在做饭、正在午睡被吵醒，只会让他讨厌这个 App。
 *
 *  点通知 → 直接进 DrillActivity，一路点完 3 分钟。
 *  发完通知立刻重排下一次（周日 10 点），不依赖开机广播。
 * ============================================================================
 */
public class DrillReceiver extends BroadcastReceiver {

    private static final String CH_ID = "yindun_drill";

    @Override
    public void onReceive(Context c, Intent intent) {
        show(c);
        DrillAlarm.schedule(c);   // 立刻排下一周，避免漏掉
    }

    private void show(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Android 8 起必须有渠道
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CH_ID, "反诈演练提醒", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("每周提醒做一次 3 分钟的反诈演练");
            nm.createNotificationChannel(ch);
        }

        /* ④ 上次那通诈骗电话如果刚复盘完，这次就不说"该演练了"这种空话，
           直接说那通电话：套路名是她自己经历过的，比任何通用文案都难忽略。
           超过 3 天（或已经练过）就退回通用提醒 —— 见 DrillPending 的过期策略。 */
        final boolean hasCase = DrillPending.fresh(c);
        final String type = hasCase ? DrillPending.type(c) : "";

        Intent open = new Intent(c, DrillActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (hasCase) {
            // 进页面直接开局，不用她再点一次"按我上传的演"
            open.putExtra(DrillActivity.EXTRA_MODE, "case");
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        // 两个请求码分开：带案例和不带案例的 PendingIntent 不能互相复用，
        // 否则上一周的 extras 会被粘到这一周的通知上
        PendingIntent pi = PendingIntent.getActivity(c, hasCase ? 9528 : 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(c, CH_ID);
        } else {
            b = new Notification.Builder(c);
        }

        if (hasCase) {
            b.setContentTitle("上次那通「" + type + "」，陪您再练一遍")
             .setContentText("同样的话术换个说法还会再来。3 分钟，练到您能一眼识破。")
             .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
             .setAutoCancel(true)
             .setContentIntent(pi);
        } else {
            b.setContentTitle("该做一次反诈演练了")
             .setContentText("3 分钟，练一练怎么对付骗子。练过的老人，被骗的概率低很多。")
             .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
             .setAutoCancel(true)
             .setContentIntent(pi);
        }

        try {
            nm.notify(9527, b.build());
        } catch (Exception ignore) { }
    }
}
