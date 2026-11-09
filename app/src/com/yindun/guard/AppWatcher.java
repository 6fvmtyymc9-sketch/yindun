package com.yindun.guard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * AppWatcher —— 前台应用采集器。App 的第三只眼睛，也是最准的一只
 * ============================================================
 * 【它盯什么】
 *   老人手机屏幕上现在显示的是哪个 App。
 *
 * 【为什么这只眼睛最值钱】
 *   骗子可以说"你去把银行 App 打开"，我们听不到这句话。
 *   但她手指一按，手机前台就变了 —— 这个动作骗不了人。
 *
 *   而且它比"听"更早、更准：
 *     · 更早：她说出口之前，手指已经按下去了
 *     · 更准：骗子可以换说法（"工资卡那个 App""你平时查余额那个"），
 *             但包名换不了
 *     · 更干净：一个字的内容都不碰，答辩时这是加分项
 *
 * 【为什么"通话中 + 打开银行"就等于危险】
 *   正常人去银行 App 的时候，不会同时在打 45 分钟电话。
 *   这两件事在生活里是互斥的。
 *
 * 【它同时兼职查无障碍权限】
 *   装了远程控制软件只是第一步，第二步是给它开无障碍权限。
 *   这两步之间常常隔几分钟（骗子在电话里一步步教）。
 *   所以这里定期查一次，别错过了第二步。
 * ============================================================
 */
public class AppWatcher {

    private static final String TAG = "YinDun";
    private static AppWatcher inst = null;

    /** 通话中 2 秒查一次（这时候每一秒都可能要命） */
    private static final long FAST_MS = 2000;
    /** 平时 10 秒查一次（省电，老人手机最怕耗电） */
    private static final long SLOW_MS = 10000;
    /** 每查这么多次，顺便看一眼无障碍权限 */
    private static final int A11Y_EVERY = 5;

    public static synchronized void start(Context c) {
        if (inst == null) inst = new AppWatcher(c.getApplicationContext());
        inst.begin();
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int counter = 0;
    private String lastPkg = null;

    private AppWatcher(Context c) {
        this.ctx = c;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                scan();
            } catch (Exception e) {
                Log.e(TAG, "扫前台应用出错", e);
            }
            // 通话中查勤快些，平时慢些
            long delay = Guard.get(ctx).st.inCall() ? FAST_MS : SLOW_MS;
            handler.postDelayed(this, delay);
        }
    };

    private void begin() {
        if (running) return;
        running = true;
        handler.postDelayed(ticker, 1000);
        Log.i(TAG, "前台应用监听已启动");
    }

    public static synchronized void stop() {
        if (inst == null) return;
        inst.handler.removeCallbacks(inst.ticker);
        inst.running = false;
    }

    private void scan() {
        counter++;
        if (counter % A11Y_EVERY == 0) checkA11y();

        String pkg = foreground();
        if (pkg == null || pkg.length() == 0) return;
        if (pkg.equals(lastPkg)) return;    // 没换，不用重复处理
        lastPkg = pkg;

        Guard g = Guard.get(ctx);
        if (Judge.isBank(pkg, null)) {
            g.onBankForeground(pkg, InstallReceiver.label(ctx, pkg));
        } else {
            g.onLeaveBank();
        }
    }

    /**
     * 查当前前台是哪个 App。
     *
     * 【原理】
     * 系统会记录每一次"某个界面来到前台"的事件。我们问它：
     * "最近 10 秒有哪些界面来过前台？" 最后那个就是现在这个。
     *
     * 【没有权限时】
     * 返回空列表，也就是永远查不到。这不影响别的功能，只是"看动作"这只眼睛瞎了。
     * 所以装机引导里必须让用户开"使用情况访问权限"，否则这一整块白做。
     */
    private String foreground() {
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;

            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 10000, now);
            if (events == null) return null;

            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                // 1 = ACTIVITY_RESUMED（API 29 后的新名字，旧名字叫 MOVE_TO_FOREGROUND，值都是 1）
                if (e.getEventType() == 1) {
                    last = e.getPackageName();
                }
            }
            return last;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 定期查：有哪个无障碍服务开着。
     *
     * 【为什么要查"全部开着的服务"而不是"某个特定包"】
     * 因为骗子让我们装的那个软件，包名我们事先不知道。
     * 但我们知道一件事：老人手机上原本不该有任何第三方无障碍服务。
     * 只要出现一个不是系统自带的，就可疑。
     */
    private void checkA11y() {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return;

            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

            boolean anyThirdParty = false;
            String pkg = null;
            for (AccessibilityServiceInfo info : list) {
                if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
                String p = info.getResolveInfo().serviceInfo.packageName;
                if (p == null) continue;
                if (p.startsWith("com.android.") || p.startsWith("com.google.")) continue;  // 系统自带，跳过
                anyThirdParty = true;
                pkg = p;
                break;
            }

            Guard g = Guard.get(ctx);
            // 只有"装过非商店软件"的前提下，这个授权才有意义（孤证不立）
            if (anyThirdParty && g.st.unknownInstall) {
                g.onA11y(true, pkg);
            } else if (!anyThirdParty) {
                g.onA11y(false, pkg);
            }
        } catch (Exception ignored) {
        }
    }
}
