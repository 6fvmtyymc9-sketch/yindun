package com.yindun.child;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.List;

/**
 * 前台服务：只做一件事——把进程钉住，让 SSE 那条线程不被系统杀掉。
 *
 * 【为什么非要它】
 * Android 8 之后，App 退到后台几分钟就会被冻结，长连接直接断。
 * 子女端如果只能在「App 打开着」的时候收消息，那就废了——
 * 现实里子女不会一直盯着这个界面，她是收到通知才打开。
 *
 * 加了前台服务，通知栏会常驻一条「银盾正在守护」，
 * 这既是保活手段，也是告诉用户「这个 App 确实在工作」，不算偷偷摸摸。
 */
public class WatchService extends Service {

    private static final String TAG = "YinDunChild";
    private static final int ID_FOREGROUND = 1001;

    private Hub.Listener listener;

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.init(this);

        // 先挂上常驻通知，再干别的：超过 5 秒不 startForeground 会被系统判 ANR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_FOREGROUND, Notify.bg(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_FOREGROUND, Notify.bg(this));
        }

        listener = new Hub.Listener() {
            @Override public void onList(List<Alert> list) { }

            @Override public void onNew(Alert a) {
                // 这里是「响铃」的唯一落点：不管 App 在前台还是后台，
                // 只要新提醒到了就弹通知。前台时通知会被界面挡住，不影响。
                Notify.alert(WatchService.this, a);
            }

            @Override public void onState(String state) { }
        };

        Hub.get().start(this);
        Hub.get().addListener(listener);
        Log.i(TAG, "守护服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统杀掉后自动重建，用户不需要手动再开一次
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Hub.get().removeListener(listener);
        Log.i(TAG, "守护服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
