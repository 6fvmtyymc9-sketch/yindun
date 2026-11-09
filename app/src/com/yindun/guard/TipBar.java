package com.yindun.guard;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * TipBar —— 通话中"轻提醒"浮条
 * ============================================================
 * 【为什么需要它】
 *
 * 银盾一共有四级：watch（留意）/ warn（警告）/ high / block（阻断）。
 *
 * 之前只有 block 是有画面的——盖屏。中间这两级是这样处理的：
 * 推一张卡给子女端 + 发一条系统通知 + 振一下。
 *
 * 问题是：老人正把手机贴在耳朵上通话，**系统通知她根本看不见**。
 * 通话界面是全屏的，通知在状态栏里，通话中没人会拉下来看。
 * 结果就是：从"聊了 5 分钟"到"聊了 8 分钟"，老人端一点反应都没有，
 * 直到突然被一整块红屏盖住——中间两级等于不存在。
 *
 * 【为什么不做成弹窗】
 *
 * 弹窗要抢焦点、要老人点确认，通话中这是打断而不是提醒。
 * 这里做成一条贴在屏幕上方、不可点、几秒后自己消失的条：
 * 老人抬眼就能看见字，但不影响她接听/挂断（NOT_FOCUSABLE + NOT_TOUCH_MODAL）。
 *
 * 【为什么它敢自己消失】
 *
 * 它只是"让你知道一下"，不是"要你做什么"。
 * 要老人做的事只有一件——等女儿的电话。那件事由红屏来说。
 * 一条提醒挂在那儿不走，反过来会逼老人去点它。
 * ============================================================
 */
public class TipBar {

    private static final String TAG = "YinDun";

    /** 挂多久自己收起来 */
    private static final long AUTO_HIDE_MS = 9000;

    private static View view = null;
    private static boolean showing = false;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static final Runnable autoHide = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    public static void show(Context ctx, String level, String title, String sub) {
        show(ctx, level, title, sub, false);
    }

    /**
     * 从屏幕下方弹。
     *
     * 【为什么需要这个】短信、微信这类"消息进来"的提醒，屏幕上同时还有系统自己的
     * 通知横幅（Google Messages 那条带 Reply 按钮的会挂十几秒）。两者都在顶部，
     * 我们的浮条必然被整条压住——实测截图上一个字都看不见。
     * 改成从下方弹就互不干扰：上面是"来短信了"，下面是"这条是诈骗"。
     */
    public static void showBottom(Context ctx, String level, String title, String sub) {
        show(ctx, level, title, sub, true);
    }

    /**
     * 弹一条。
     *
     * @param level  watch / warn
     * @param title  主句，一句话说清楚现在怎么了
     * @param sub    副句，告诉她要不要做点什么
     * @param bottom true = 贴屏幕下方，false = 贴屏幕上方
     */
    private static void show(Context ctx, String level, String title, String sub,
                             boolean bottom) {
        if (!Shield.canOverlay(ctx)) {
            Log.w(TAG, "轻提醒跳过：没有悬浮窗权限");
            return;
        }
        // 阻断屏已经盖上了就别弹了，红屏比这个重要
        if (Shield.showing()) return;

        hide();   // 上一条先撤，避免叠两条

        boolean warn = "warn".equals(level) || "high".equals(level);
        int accent = warn ? 0xFFF97316 : 0xFFEAB308;   // 橙 / 黄
        // 【2026-11-09 改】原来 warn 一律用 ⏱（那时 warn 只来自"通话超时"）。
        // 现在微信诈骗也走 warn，用钟表图标会让人以为是在说通话时长。
        // 按内容分：讲时间的用 ⏱，其它一律用 ⚠。
        boolean timeTalk = title != null && title.contains("通话");
        String icon = timeTalk ? "⏱" : "⚠";

        try {
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF2101723);                       // 深色，压得住通话界面
            bg.setCornerRadius(dp(ctx, 18));
            bg.setStroke(dp(ctx, 2), accent);
            root.setBackground(bg);
            root.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));

            // 左边一个色块图标：不靠图片，省体积
            TextView ic = new TextView(ctx);
            ic.setText(icon);
            ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            ic.setTextColor(accent);
            ic.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pi = new LinearLayout.LayoutParams(
                    dp(ctx, 40), dp(ctx, 40));
            pi.rightMargin = dp(ctx, 12);
            root.addView(ic, pi);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView t1 = new TextView(ctx);
            t1.setText(title);
            // 【2026-11-07 放大】18→20sp。这条浮条只在屏幕上挂 9 秒，
            // 服务对象是正在通话、没戴老花镜的老人，字小了等于没提醒。
            t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            t1.setTextColor(0xFFFFFFFF);
            t1.setTypeface(null, android.graphics.Typeface.BOLD);
            // 悬浮窗里文字一律给死高度，否则会被量成 0 高（熟人卡踩过这个坑）
            t1.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 36)));

            TextView t2 = new TextView(ctx);
            t2.setText(sub);
            // 【2026-11-07 放大】14→16sp，同上
            t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t2.setTextColor(0xFFC9D3E2);
            LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 30));
            p2.topMargin = dp(ctx, 2);
            t2.setLayoutParams(p2);

            col.addView(t1);
            col.addView(t2);
            root.addView(col, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ctx.getResources().getDisplayMetrics().widthPixels - dp(ctx, 32),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // 不抢焦点、不挡触摸：老人照常接听挂断
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            if (bottom) {
                // 贴底：留 140dp 出来，躲开导航栏和全面屏手势区，也躲开系统通知横幅
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.y = dp(ctx, 140);
            } else {
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                // 【2026-11-07 上移】原来看 76dp，实测把通话界面的号码
                // "1 380-000-1111" 压掉了上半截，只露下半截字——比不提醒还难看。
                // 往上提到 60dp，同时把上下内边距收窄，整条压在上面的头像上。
                // 头像只是装饰，号码是信息：宁压头像，不压号码。
                lp.y = dp(ctx, 60);
            }

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            wm.addView(root, lp);
            view = root;
            showing = true;

            Log.i(TAG, "轻提醒已弹出（" + level + "）：" + title);

            handler.removeCallbacks(autoHide);
            handler.postDelayed(autoHide, AUTO_HIDE_MS);

        } catch (Exception e) {
            Log.w(TAG, "轻提醒弹不出来：" + e.getMessage());
            view = null;
            showing = false;
        }
    }

    /** 收起。升级到阻断、通话结束、用户切换场景，都走这里 */
    public static void hide() {
        handler.removeCallbacks(autoHide);
        if (view == null) {
            showing = false;
            return;
        }
        Context ctx = view.getContext();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(view);
        } catch (Exception ignored) {
        }
        view = null;
        showing = false;
    }

    public static boolean isShowing() {
        return showing;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }
}
