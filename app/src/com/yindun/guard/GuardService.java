package com.yindun.guard;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * GuardService —— 前台服务。让守护活过一晚上
 * ============================================================
 * 【为什么必须有这个东西】
 * 国产系统（小米/OPPO/vivo/华为）会主动清理后台进程。如果 App 只是"打开过一次"，
 * 关掉屏幕过两小时，进程就被杀了。
 *
 * 杀掉的后果不是"功能少了点"，是**老人被骗那一刻，手机上什么都没发生**。
 * 演示前一天晚上装上，第二天一演示——死的。这是最常见的翻车方式。
 *
 * 前台服务就是告诉系统："这个进程用户知道它在跑，别动它。"
 * 代价是状态栏常驻一条通知。这个代价必须付。
 *
 * 【顺带解决另一件事】
 * 通话监听和前台应用监听，如果挂在界面上（MainActivity），
 * 用户一按返回就没了。挂在这个服务上，界面关了它们照样跑。
 * ============================================================
 */
public class GuardService extends Service {

    private static final String TAG = "YinDun";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "守护服务启动");

        // 1. 挂上"守护中"那条常驻通知（这一步做了才算前台服务）
        try {
            Notifier.ensureChannel(this);
            startForeground(Notifier.NOTIFY_FOREVER, Notifier.buildForeground(this));
        } catch (Exception e) {
            Log.e(TAG, "启动前台通知失败", e);
        }

        // 2. 初始化判定引擎（读 rules.json，顺便暴露规则错误）
        Judge j = Judge.get(this);
        if (!j.loaded) {
            Log.e(TAG, "规则加载失败：" + j.loadError);
        }

        // 3. 把两只眼睛睁开
        CallWatcher.start(this);   // 通话状态 + 时长 + 拒接家人
        AppWatcher.start(this);    // 前台应用 + 无障碍状态
    }

    /**
     * START_STICKY：被系统杀掉后，系统会尽量把我们拉起来。
     * 这不是万能的（国产系统杀得更狠），但比不写强。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "守护服务被销毁");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
