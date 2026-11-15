package com.yindun.child;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BootReceiver —— 子女端开机自启
 * ============================================================
 * 【为什么子女端也需要】
 * 子女端靠一条 SSE 长连接收预警，那条线由 WatchService 的进程钉着。
 * 手机重启，进程没了，连接也就没了。
 *
 * 后果是：老人那边出了事、云端把预警推上来了，子女手机上**一声不响**。
 * 她不会知道"我错过了什么"，界面上只是一片空白 —— 而空白看起来
 * 跟"妈妈今天没事"一模一样。这是最坏的一种失灵：悄无声息。
 *
 * 【为什么只在已配对之后才自启】
 * 没配对的时候 Conf.childToken() 是空的，连上去也只会 401，
 * 拉起服务纯属浪费电，还会让通知栏多一条没意义的东西。
 * ============================================================
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "YinDunChild";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        try {
            // 【必须先 init】Conf 是个单例，SharedPreferences 是懒加载的 ——
            // 平时由 MainActivity/WatchService 初始化，可开机广播会**先于它们**执行，
            // 这时候 sp 还是 null，直接问 paired() 就是空指针。
            // 这个坑实测踩到了：receiver 起来、进程活了、然后 NPE 崩在 Conf.elders()。
            Conf.get().init(ctx);

            if (!Conf.get().paired()) {
                Log.i(TAG, "开机了，但还没配对过老人，不拉起监听");
                return;
            }

            Intent svc = new Intent(ctx, WatchService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
            Log.i(TAG, "开机自启：预警监听已拉起");
        } catch (Exception e) {
            Log.e(TAG, "开机自启失败，等她下次打开银盾家人再起", e);
        }
    }
}
