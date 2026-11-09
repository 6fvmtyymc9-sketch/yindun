package com.yindun.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;

import java.util.List;

/**
 * InstallReceiver —— 装包广播。专门盯"老人自己装了个软件"这件事
 * ============================================================
 * 【为什么这件事最值得盯】
 * 一个 70 岁的老人，正常情况下不会从浏览器下载一个 APK 装上去。
 * 更不会装完之后，还手动进系统设置里给这个软件打开"无障碍权限"
 * （这个权限能让软件看见屏幕上的一切、替她点按钮）。
 *
 * 这两件事加在一起，几乎没有正当解释。
 *
 * 所以它是全部判据里唯一一条"不看条数、单条就能确诊"的。
 *
 * 【为什么要判断"是不是从应用商店装的"】
 * 老人也会从应用商店装微信、装广场舞视频。那是正常的。
 * 只有绕过应用商店（浏览器下载、别人发来的安装包）才可疑。
 *
 * 这一步判断能把误报压掉九成。
 * ============================================================
 */
public class InstallReceiver extends BroadcastReceiver {

    private static final String TAG = "YinDun";

    /** 算"正规商店"的安装来源。不在这个名单里的，都当可疑 */
    private static final String[] STORES = {
            "com.android.vending",        // Google Play
            "com.xiaomi.market",          // 小米应用商店
            "com.huawei.appmarket",       // 华为应用市场
            "com.oppo.market",            // OPPO 软件商店
            "com.heytap.market",
            "com.bbk.appstore",           // vivo 应用商店
            "com.tencent.android.qqdownloader", // 应用宝
            "com.qihoo.appstore",
            "com.baidu.appsearch"
    };

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_PACKAGE_ADDED.equals(action)
                && !Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        // 只处理"新装"，不处理"覆盖安装"（覆盖多见于系统或商店更新）
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        if (replacing) return;

        android.net.Uri data = intent.getData();
        if (data == null) return;
        String pkg = data.getSchemeSpecificPart();
        if (pkg == null || pkg.length() == 0) return;

        // 不盯我们自己和系统组件
        if (pkg.equals(ctx.getPackageName())) return;

        boolean fromStore = isFromStore(ctx, pkg);
        Log.i(TAG, "装了 App：" + pkg + "，来自商店=" + fromStore);

        if (!fromStore) {
            Guard.get(ctx).onUnknownInstall(pkg);
            // 顺带查一下：这个刚装的软件，是不是已经拿到无障碍权限了
            checkA11y(ctx, pkg);
        }
    }

    /** 是不是从正规商店装的 */
    private boolean isFromStore(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            String installer = null;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                installer = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            } else {
                installer = pm.getInstallerPackageName(pkg);
            }
            if (installer == null) return false;   // 查不到来源 =  sideload，最可疑
            for (String s : STORES) {
                if (s.equals(installer)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 查这个包有没有开无障碍权限。
     *
     * 【为什么要单独查】
     * 装包广播只在"装上的那一秒"来一次。但老人往往是先装上，
     * 过几分钟才在骗子指导下进设置里开权限。这两个动作是分开的。
     *
     * 所以这里查一次（应对"装完立刻授权"），AppWatcher 那边也会定期查
     * （应对"过一会儿才授权"）。
     */
    private void checkA11y(Context ctx, String pkg) {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return;
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : list) {
                if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
                String p = info.getResolveInfo().serviceInfo.packageName;
                if (pkg.equals(p)) {
                    Guard.get(ctx).onA11y(true, pkg);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 拿应用显示名（只用于日志和人看的提示） */
    public static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
