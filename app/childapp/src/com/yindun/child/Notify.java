package com.yindun.child;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Vibrator;

/**
 * 通知、提示音、振动。
 *
 * 【为什么响铃和振动都要做】
 * 子女把手机放桌上、正在做别的事，屏幕亮了不一定看得到；
 * 但响一声 + 抖一下，人一定会去拿手机。这正是这个 App 存在的意义。
 */
public final class Notify {

    private static final String CH_ALERT = "alert";     // 新提醒：高优先级，响铃
    private static final String CH_BG = "bg";           // 常驻「守护中」：静音、不可点掉
    private static final int ID_BG = 1001;

    private static boolean inited = false;

    private Notify() { }

    public static void init(Context ctx) {
        if (inited) return;
        inited = true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // ---- 提醒渠道：高优先级，允许横幅 + 声音 + 振动 ----
        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, "家人提醒", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("妈妈手机发现可疑情况时立刻提醒");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 260, 140, 260});
        alert.enableLights(true);
        alert.setShowBadge(true);
        // 用系统默认通知音，不打包音频文件：真机上用户换铃声它也跟随
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (sound != null) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            alert.setSound(sound, attrs);
        }
        nm.createNotificationChannel(alert);

        // ---- 常驻渠道：低优先级，不响不震，用户知道有这个 App 在跑 ----
        NotificationChannel bg = new NotificationChannel(
                CH_BG, "守护状态", NotificationManager.IMPORTANCE_LOW);
        bg.setDescription("保持与妈妈手机的连接");
        bg.setShowBadge(false);
        nm.createNotificationChannel(bg);
    }

    /** 前台服务用的那条常驻通知 */
    public static Notification bg(Context ctx) {
        Notification.Builder b = builder(ctx, CH_BG)
                .setContentTitle("银盾正在守护")
                .setContentText("妈妈手机上的可疑情况会立刻推到这里")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .setShowWhen(false);
        Intent it = new Intent(ctx, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setContentIntent(pi);
        return b.build();
    }

    /** 收到一条新提醒：弹通知 + 响铃 + 振动 */
    public static void alert(Context ctx, Alert a) {
        init(ctx);

        // 标题就用这条提醒自己的标题（"妈妈收到可疑短信"），
        // 别在这里另写一句"妈妈可能正在被诈骗"—— 会和正文重复，
        // 第一版就是标题正文都成了"妈妈收到可疑…"，看着像 bug。
        // 只在高危/阻断级别前面加个前缀，让子女一眼看出严重程度。
        String head = a.title != null && a.title.length() > 0 ? a.title : "妈妈手机有情况";
        if ("block".equals(a.level)) head = "已拦下 · " + head;
        else if ("high".equals(a.level)) head = "高危 · " + head;

        Notification.Builder b = builder(ctx, CH_ALERT)
                .setContentTitle(head)
                .setContentText(a.body)
                .setStyle(new Notification.BigTextStyle().bigText(a.body))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_ALARM)
                .setDefaults(Notification.DEFAULT_VIBRATE);

        Intent it = new Intent(ctx, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) (a.id % 1000 + 10), it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setContentIntent(pi);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            int nid = a.id > 0 ? (int) (a.id % 100000) + 2000 : (int) (System.currentTimeMillis() % 100000) + 2000;
            nm.notify(nid, b.build());
        }

        vibrate(ctx, a);
        beep(ctx);
    }

    private static void vibrate(Context ctx, Alert a) {
        if (!Conf.get().sound()) return;         // 「静默」开关一关，铃声和振动一起停
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        // 等级越高抖得越久：block 连抖三下，warn 只抖一下
        long[] pattern;
        if ("block".equals(a.level)) pattern = new long[]{0, 320, 160, 320, 160, 320};
        else if ("high".equals(a.level)) pattern = new long[]{0, 300, 160, 300};
        else pattern = new long[]{0, 220};
        v.vibrate(pattern, -1);
    }

    private static void beep(Context ctx) {
        if (!Conf.get().sound()) return;
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (u == null) return;
            Ringtone r = RingtoneManager.getRingtone(ctx.getApplicationContext(), u);
            if (r != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.setLooping(false);
                r.play();
            }
        } catch (Exception ignored) {
            // 静音模式下 play() 本来就没声音，不算错误
        }
    }

    private static Notification.Builder builder(Context ctx, String channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(ctx, channel);
        }
        return new Notification.Builder(ctx);
    }
}
