package com.yindun.guard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AlertSheet —— 挂断后复盘提醒（从屏幕底部升起的一张卡）
 * ============================================================
 * 通话录音复盘判成有风险时弹出。和通话中的红屏不同：电话已经挂了，
 * 不需要拦什么，要做的是让老人明白三件事 ——
 *   1. 刚才那通电话很可能是骗子
 *   2. 骗子走到了第几步（用 4 段进度条表示）
 *   3. 对方说了什么（直接引用原话，比任何解释都有说服力）
 * 然后给两个按钮：打给女儿 / 知道了。
 *
 * 文案只说真在发生的事：推送成功才写"女儿已经收到提醒"。
 * ============================================================
 */
public class AlertSheet {

    private static final String TAG = "YinDun";
    private static final long SAFETY_HIDE_MS = 5 * 60 * 1000L;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static View view = null;

    private static final Runnable safetyHide = new Runnable() {
        @Override
        public void run() {
            if (view != null) hide(view.getContext());
        }
    };

    private static final String[] STAGE_NAME = {"", "建立信任", "制造恐慌", "隔离家人", "索要钱款"};
    private static final String[] STAGE_SAY = {
            "",
            "当前阶段：建立信任，打探个人信息",
            "当前阶段：制造恐慌，向您施加压力",
            "当前阶段：要求保密，隔离家人",
            "当前阶段：索要钱款，已进入最后一步"};
    private static final int[] STAGE_COLOR = {0xFFF79009, 0xFFF97066, 0xFFF04438, 0xFFB42318};

    /**
     * @param level    warn / high / block
     * @param stage    1..4
     * @param quotes   对方原话（最多显示 2 句）
     * @param advice   云端给的一句建议，可空
     * @param pushed   子女端是否已经收到
     */
    public static void show(Context ctx, String level, int stage, String type,
                            String[] quotes, String advice, boolean pushed) {
        if (view != null) hide(ctx);
        if (!Shield.canOverlay(ctx)) {
            Log.w(TAG, "复盘提醒跳过：没有悬浮窗权限");
            return;
        }
        try {
            view = build(ctx.getApplicationContext(), level, Math.max(1, Math.min(4, stage)), type, quotes, advice, pushed);
            int typeW = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, typeW,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    android.graphics.PixelFormat.TRANSLUCENT);
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(view, lp);
            // 卡片从底部升起
            final View card = view.findViewWithTag("card");
            if (card != null) {
                card.setTranslationY(dp(ctx, 600));
                card.animate().translationY(0).setDuration(320)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            }
            handler.removeCallbacks(safetyHide);
            handler.postDelayed(safetyHide, SAFETY_HIDE_MS);
            Log.i(TAG, "复盘提醒已弹出：S" + stage + "/" + level);
        } catch (Exception e) {
            Log.e(TAG, "复盘提醒弹出失败", e);
            view = null;
        }
    }

    public static void hide(Context ctx) {
        handler.removeCallbacks(safetyHide);
        View v = view;
        view = null;
        if (v == null) return;
        try {
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (Exception ignored) {
        }
    }

    public static boolean showing() {
        return view != null;
    }

    // ==========================================================

    private static View build(final Context ctx, String level, int stage, String type,
                              String[] quotes, String advice, boolean pushed) {
        boolean hot = "high".equals(level) || "block".equals(level);

        FrameLayout dim = new FrameLayout(ctx);
        dim.setBackgroundColor(0x8C0B1220);

        LinearLayout card = new LinearLayout(ctx);
        card.setTag("card");
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(Color.WHITE);
        float r = dp(ctx, 30);
        cbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(cbg);
        card.setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 28));
        FrameLayout.LayoutParams pc = new FrameLayout.LayoutParams(-1, -2);
        pc.gravity = Gravity.BOTTOM;
        dim.addView(card, pc);

        // ---------- 抬头 ----------
        LinearLayout head = row(ctx);
        head.addView(AlarmView.mark(ctx), new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));
        TextView brand = tv(ctx, "银盾 · 通话复盘", 15, 0xFF475467, true);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(0, -2, 1f);
        pb.leftMargin = dp(ctx, 8);
        head.addView(brand, pb);
        head.addView(tv(ctx, new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()), 15, 0xFF667085, false));
        card.addView(head);

        // ---------- 风险标签 + 大标题 ----------
        TextView badge = tv(ctx, hot ? "高风险" : "中风险", 15, hot ? 0xFFB42318 : 0xFFB54708, true);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(hot ? 0xFFFEE4E2 : 0xFFFEF0C7);
        bbg.setCornerRadius(dp(ctx, 12));
        badge.setBackground(bbg);
        badge.setPadding(dp(ctx, 10), dp(ctx, 3), dp(ctx, 10), dp(ctx, 3));
        LinearLayout.LayoutParams pBadge = new LinearLayout.LayoutParams(-2, -2);
        pBadge.topMargin = dp(ctx, 16);
        card.addView(badge, pBadge);

        TextView title = tv(ctx, hot ? "刚才的通话\n疑似诈骗" : "刚才的通话\n存在可疑内容", 30, 0xFF0B1220, true);
        title.setLineSpacing(dp(ctx, 4), 1f);
        LinearLayout.LayoutParams pt = new LinearLayout.LayoutParams(-1, -2);
        pt.topMargin = dp(ctx, 10);
        card.addView(title, pt);

        // ---------- 骗局进度：4 段 ----------
        LinearLayout bars = row(ctx);
        LinearLayout labels = row(ctx);
        for (int i = 1; i <= 4; i++) {
            View b = new View(ctx);
            GradientDrawable g = new GradientDrawable();
            g.setColor(i <= stage ? STAGE_COLOR[i - 1] : 0xFFE4E8EE);
            g.setCornerRadius(dp(ctx, 4));
            b.setBackground(g);
            LinearLayout.LayoutParams pbar = new LinearLayout.LayoutParams(0, dp(ctx, 8), 1f);
            if (i > 1) pbar.leftMargin = dp(ctx, 6);
            bars.addView(b, pbar);
            TextView l = tv(ctx, STAGE_NAME[i], 15, i == stage ? 0xFFB42318 : 0xFF667085, i == stage);
            l.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 1) pl.leftMargin = dp(ctx, 6);
            labels.addView(l, pl);
        }
        LinearLayout.LayoutParams pBars = new LinearLayout.LayoutParams(-1, -2);
        pBars.topMargin = dp(ctx, 16);
        card.addView(bars, pBars);
        LinearLayout.LayoutParams pLab = new LinearLayout.LayoutParams(-1, -2);
        pLab.topMargin = dp(ctx, 6);
        card.addView(labels, pLab);
        TextView say = tv(ctx, STAGE_SAY[stage], 18, stage >= 3 ? 0xFFB42318 : 0xFFB54708, true);
        LinearLayout.LayoutParams pSay = new LinearLayout.LayoutParams(-1, -2);
        pSay.topMargin = dp(ctx, 8);
        card.addView(say, pSay);

        // ---------- 对方原话 ----------
        int shown = 0;
        if (quotes != null) {
            for (String q : quotes) {
                if (q == null || q.trim().length() == 0 || shown >= 2) continue;
                if (shown == 0) {
                    TextView lab = tv(ctx, "通话中的关键语句", 15, 0xFF667085, false);
                    LinearLayout.LayoutParams pq = new LinearLayout.LayoutParams(-1, -2);
                    pq.topMargin = dp(ctx, 14);
                    card.addView(lab, pq);
                }
                TextView qt = tv(ctx, "“" + q.trim() + "”", 20, 0xFF0B1220, false);
                qt.setLineSpacing(dp(ctx, 3), 1f);
                GradientDrawable qbg = new GradientDrawable();
                qbg.setColor(0xFFF3F5F8);
                qbg.setCornerRadius(dp(ctx, 16));
                qt.setBackground(qbg);
                qt.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
                LinearLayout.LayoutParams pq = new LinearLayout.LayoutParams(-1, -2);
                pq.topMargin = dp(ctx, 8);
                card.addView(qt, pq);
                shown++;
            }
        }

        // ---------- 建议 ----------
        String tip = (advice == null || advice.trim().length() == 0) ? "如有疑问，请先联系家人核实。" : advice.trim();
        TextView adv = tv(ctx, "请暂缓转账。" + tip, 20, 0xFF344054, false);
        adv.setLineSpacing(dp(ctx, 3), 1f);
        LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(-1, -2);
        pa.topMargin = dp(ctx, 14);
        card.addView(adv, pa);

        // ---------- 按钮 ----------
        LinearLayout btns = row(ctx);
        Button call = btn(ctx, "联系女儿", true);
        Button ok = btn(ctx, "我已知晓", false);
        btns.addView(call, new LinearLayout.LayoutParams(0, dp(ctx, 66), 3f));
        LinearLayout.LayoutParams pOk = new LinearLayout.LayoutParams(0, dp(ctx, 66), 2f);
        pOk.leftMargin = dp(ctx, 10);
        btns.addView(ok, pOk);
        LinearLayout.LayoutParams pBtns = new LinearLayout.LayoutParams(-1, -2);
        pBtns.topMargin = dp(ctx, 18);
        card.addView(btns, pBtns);
        call.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide(ctx);
                Notifier.callChild(ctx);
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide(ctx);
            }
        });

        TextView foot = tv(ctx, pushed ? "✓ 已同步通知您的女儿" : "未能通知女儿，请主动与她联系",
                16, pushed ? 0xFF0F7A4A : 0xFF667085, pushed);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pf = new LinearLayout.LayoutParams(-1, -2);
        pf.topMargin = dp(ctx, 14);
        card.addView(foot, pf);

        return dim;
    }

    private static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private static TextView tv(Context ctx, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private static Button btn(Context ctx, String s, boolean primary) {
        Button b = new Button(ctx);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 22 : 20);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : 0xFF0B1220);
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? 0xFF0A1A33 : Color.WHITE);
        g.setCornerRadius(dp(ctx, 20));
        if (!primary) g.setStroke(dp(ctx, 1), 0xFFDDE2EA);
        b.setBackground(g);
        return b;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
