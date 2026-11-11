package com.yindun.guard;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

    /** 网络回调要留着引用，注销时用得上。 */
    private ConnectivityManager.NetworkCallback netCb;

    /** 定时补发的心跳，60 秒一次。见 startOutboxTicker 里的解释。 */
    private Handler ticker;
    private static final long OUTBOX_TICK_MS = 60 * 1000L;

    @Override
    public void onCreate() {
        super.onCreate();
        Stats.init(this);
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

        // 4. 开一条能"往下收"的线：子女远程干预（提醒妈妈 / 锁定转账）
        //    【为什么放在服务里】子女按按钮的那一刻，老人的 App 界面多半是关着的。
        //    挂在 Activity 上，这条线就跟着界面一起没了 —— 那就等于功能不存在。
        CommandLink.start(this);

        // 5. 把家人录的那一句提前取到手机上。
        //    【为什么必须现在取】阻断那一刻只有几秒钟，现下载来不及 —— 钱已经转了。
        //    内部有 6 小时节流，重复调用不会一直发请求。
        FamilyVoice.refresh(this, false);

        // 6. 断网期间没送出去的预警，现在补发；并盯着网络恢复的那一刻再补一次。
        //    【为什么必须有】老人家里 WiFi 断一晚上，这一晚上发生的预警原来全丢了，
        //    而且是静默丢的 —— 子女端一片空白，谁也不知道错过什么。
        Outbox.flush(this);
        watchNetwork();
        startOutboxTicker();

        // 7. 预热端侧小模型（docs/模型选型.md 4.1）。
        //    【为什么现在热】首次要从 assets 拷 44MB 出来，手机上要上百毫秒到秒级；
        //    等第一条短信到了才加载，那条短信就只能靠规则 —— 恰好是最该抓住的那条。
        //    放子线程，别卡住服务启动。机器架构不支持时它会立刻返回 false 并记下原因。
        new Thread(new Runnable() {
            @Override
            public void run() {
                TinyBert.warmUp(getApplicationContext());
                Log.i(TAG, TinyBert.status());
            }
        }, "tinybert-warmup").start();
    }

    /**
     * 定时兜底补发。
     *
     * 【为什么光有 NetworkCallback 还不够】实测过：把网卡断掉再恢复，
     * 回调压根不触发。更要命的是真实场景里最常见的那种断网 ——
     * **Wi-Fi 还连着、路由器外网掉了**、或者宽带欠费 —— 手机这边的网络状态
     * 一点没变，onAvailable 永远不会来。那种情况下队列就会一直躺着。
     * 所以必须有一个"隔一会儿就试一次"的循环兜底。
     *
     * 【为什么是 60 秒】比它短会一直发无用请求耗电；比它长，
     * 网恢复后子女端要等太久才收到。60 秒是个折中。
     */
    private void startOutboxTicker() {
        if (ticker != null) return;
        ticker = new Handler(Looper.getMainLooper());
        ticker.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 队列空的时候 flush 内部会直接返回，不会起多余的头
                Outbox.flush(GuardService.this);
                if (ticker != null) ticker.postDelayed(this, OUTBOX_TICK_MS);
            }
        }, OUTBOX_TICK_MS);
    }

    /**
     * 网络恢复的那一刻触发补发。
     *
     * 【为什么不用广播 CONNECTIVITY_ACTION】Android 7 起注册方式就废弃了，
     * 9 以后连静态广播都收不到。NetworkCallback 是现在唯一可靠的做法。
     */
    private void watchNetwork() {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "网络恢复，补发积压的预警");
                    Outbox.flush(GuardService.this);
                }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception e) {
            Log.w(TAG, "监听网络失败（补发仍会在下次启动时进行）：" + e.getMessage());
        }
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
        // 指令通道跟着服务一起停：服务没了，这条线程留着也没人能响应
        CommandLink.stop();
        if (netCb != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCb);
            } catch (Exception ignored) {
            }
            netCb = null;
        }
        if (ticker != null) {
            ticker.removeCallbacksAndMessages(null);
            ticker = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
