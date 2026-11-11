package com.yindun.guard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ============================================================
 *  Nudge —— 子女远程「提醒妈妈」的全屏提示（蓝色，不是红色）
 * ============================================================
 *
 * 【为什么不用 Shield 那套红屏】
 * 红屏是"我们已经判定你在被骗"，代表拦截。子女点「提醒妈妈」的时候，
 * 我们并不知道老人此刻是不是正在被骗 —— 可能她只是在做饭没接电话。
 * 用红屏会冤枉人，也会让老人以为手机出故障。
 * 所以这里是蓝色、温和、只有一句话：家人正在联系您，先别动手机。
 *
 * 【为什么仍然全屏 + 持续振动】
 * 老人手机常常静音扣在桌上。一条通知她看不见；
 * 全屏亮起来 + 振动，她才有可能抬眼看一眼。
 *
 * 复用 Shield 的悬浮窗做法（同一套权限判断、同一套兜底删除），
 * 但不复用它的自动拨号、不播警报音、不晃屏。
 */
final class Nudge {

    private static final String TAG = "YinDun";
    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** 无人按按钮时最多停留 3 分钟 */
    private static final long SAFETY_HIDE_MS = 3 * 60 * 1000L;
    /** 振动保险：60 秒后自己停，避免老人不在手机旁边时一直振到没电 */
    private static final long VIBRATE_MS = 60 * 1000L;

    private static View view = null;

    private static final Runnable safetyHide = new Runnable() {
        @Override public void run() {
            Log.w(TAG, "提醒卡 3 分钟没人管，自己撤掉");
            hide();
        }
    };

    private Nudge() { }

    /**
     * @param from 谁在联系她。云端不填时是"家人" —— 我们不猜是女儿还是儿子
     */
    static void show(final Context ctx, final String from) {
        // 【必须回主线程】悬浮窗要从带 Looper 的线程加，否则 addView 里
        //   new ViewRootImpl 会抛 "Can't create handler inside thread ... that has not called Looper.prepare()"。
        //   这条指令是从 SSE 线程进来的，是后台线程 —— 第一版就栽在这里。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showOnMain(ctx, from);
        } else {
            handler.post(new Runnable() {
                @Override public void run() { showOnMain(ctx, from); }
            });
        }
    }

    private static void showOnMain(Context ctx, String from) {
        if (view != null) return;          // 已经弹着了，不叠第二层
        final String who = (from == null || from.trim().length() == 0) ? "家人" : from.trim();

        if (!Shield.canOverlay(ctx)) {
            // 没悬浮窗权限：退一步，至少让她听见/看见一行字
            Log.w(TAG, "没有悬浮窗权限，提醒降级为提示条");
            TipBar.show(ctx, "warn", who + "正在联系您", "请先不要操作手机，等电话");
            Vibrate.loop(ctx, 8000);
            return;
        }

        try {
            view = build(ctx, who);
            int type = (Build.VERSION.SDK_INT >= 26)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ctx.getResources().getDisplayMetrics().widthPixels,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    android.graphics.PixelFormat.OPAQUE);
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            wm.addView(view, lp);

            view.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

            Vibrate.loop(ctx, VIBRATE_MS);
            handler.postDelayed(safetyHide, SAFETY_HIDE_MS);
            Log.i(TAG, "已弹出「" + who + "正在联系您」");
        } catch (Exception e) {
            Log.e(TAG, "提醒卡弹出失败", e);
            view = null;
        }
    }

    /** 撤掉提醒（按了"我知道了"、或者超时兜底） */
    static void hide() {
        handler.removeCallbacks(safetyHide);
        Vibrate.stop();
        if (view == null) return;
        try {
            WindowManager wm = (WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(view);
        } catch (Exception ignored) {
        }
        view = null;
        Log.i(TAG, "提醒卡已撤");
    }

    static boolean showing() {
        return view != null;
    }

    // ==========================================================
    //  界面
    // ==========================================================

    private static View build(final Context ctx, String who) {
        FrameLayout outer = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0B3D91, 0xFF14539E, 0xFF1B6ACB});
        outer.setBackground(bg);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        // 垂直居中：内容只有标题+一句话+一个按钮，全顶到上面会显得下面空了一大块，
        // 截图里第一版就是这样，像界面没加载完。
        root.setGravity(Gravity.CENTER);
        int pad = dp(ctx, 28);
        root.setPadding(pad, dp(ctx, 36), pad, dp(ctx, 36));

        // 顶部小标：说明这条提醒是谁发起的，不是系统报警
        TextView tag = new TextView(ctx);
        tag.setText("银盾 · 家人的提醒");
        tag.setTextSize(13);
        tag.setTextColor(0xCCFFFFFF);
        tag.setGravity(Gravity.CENTER);
        root.addView(tag);

        // 主体：一张白色圆角卡，把"谁在联系您"说清楚
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(dp(ctx, 22));
        card.setBackground(cardBg);
        card.setPadding(dp(ctx, 24), dp(ctx, 30), dp(ctx, 24), dp(ctx, 30));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(ctx, 28);
        root.addView(card, cp);

        TextView title = new TextView(ctx);
        title.setText(who + "正在联系您");
        title.setTextSize(28);
        title.setTextColor(0xFF0B1220);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("请先不要操作手机\n等电话打过来");
        sub.setTextSize(19);
        sub.setTextColor(0xFF344054);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(dp(ctx, 8), 1f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(ctx, 16);
        card.addView(sub, sp);

        // 一句话点明为什么：正在跟人谈钱的时候，这条提醒才有分量
        // 【字数控制】这行原来写的是"如果电话里有人让您转账、报验证码，请先挂断"，
        //   在 1080 宽的手机上正好断成"…请先挂 / 断"，一个"断"字单独一行，很难看。
        //   压到 18 个字以内，一行放得下。
        TextView why = new TextView(ctx);
        why.setText("电话里让您转账或报验证码，请先挂断");
        why.setTextSize(14);
        why.setTextColor(0xFF667085);
        why.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wp.topMargin = dp(ctx, 18);
        card.addView(why, wp);

        // 唯一的出口：白色实心按钮，一眼知道按哪里
        Button ok = new Button(ctx);
        ok.setText("我知道了");
        ok.setTextSize(19);
        ok.setTextColor(0xFF0B3D91);
        ok.setAllCaps(false);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(0xFFFFFFFF);
        okBg.setCornerRadius(dp(ctx, 999));
        ok.setBackground(okBg);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hide(); }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 60));
        op.topMargin = dp(ctx, 36);
        root.addView(ok, op);

        outer.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return outer;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
