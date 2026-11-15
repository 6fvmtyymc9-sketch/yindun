package com.yindun.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BootReceiver —— 开机自启。手机重启之后，守护要自己站起来
 * ============================================================
 * 【为什么这一条不能少】
 * 老人手机没电关机、系统更新重启、她自己顺手按了重启 —— 都是常事。
 * 重启之后 GuardService 不会自己回来：短信不拦了、通话不看了、
 * 前台应用不盯了、子女端那条指令线也断了。
 *
 * 这不是"功能少了一点"，是**看起来装好了、其实什么都没在跑**。
 * 桌面上那个图标还在，老人以为银盾在守着；真到被骗那一刻，
 * 手机上什么都不会发生。这比压根没装更坏 —— 她会因为"我装了银盾"而放松。
 *
 * 【为什么不无条件自启】
 * 装了 App、只是点开看了一眼就走的人有不少。对她们来说，每次开机就常驻
 * 一条"银盾守护中"，跟流氓软件没区别。所以用一个标记（Pair.enabled）
 * 记住"她真的点过开始守护"，只在那种情况下自启。
 *
 * 【Android 版本这块】
 * · Android 8 起不允许开机拉起普通后台服务，必须走 startForegroundService；
 * · Android 12 起又限制"从后台启动前台服务"，但 BOOT_COMPLETED 是官方
 *   明列的豁免场景，这条在真机上成立；
 * · 代价是必须在 5 秒内 startForeground，否则系统判 ANR 直接杀进程 ——
 *   GuardService.onCreate 第一件事就是 startForeground，来得及。
 * ============================================================
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "YinDun";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        if (!Pair.enabled(ctx)) {
            Log.i(TAG, "开机了，但老人从没开通过守护，这次不拉起");
            return;
        }

        try {
            Intent svc = new Intent(ctx, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
            Log.i(TAG, "开机自启：守护服务已拉起");
        } catch (Exception e) {
            // 个别 ROM 会拦这一下。崩掉没用，记下来，等她下次打开银盾时自会起来。
            Log.e(TAG, "开机自启失败，等她下次打开银盾再起", e);
        }
    }
}
