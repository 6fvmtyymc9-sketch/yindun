package com.yindun.guard;

import android.content.Context;
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
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Acquaint —— 问老人一句"电话那头是你认识的人吗"
 * ============================================================
 * 【一共问两次，这是 2026-11-07 定下的产品规则】
 *
 *   第一次：陌生号码响铃时。屏幕亮着、人正盯着看、手还没被占住。
 *   第二次：接通聊满 5 分钟时。这时屏幕多半被耳朵贴熄了，
 *          所以第二次必须靠振动把人叫回来看屏幕，而且
 *          **不点按钮就一直振**——不能自己停，自己停等于放她继续听。
 *
 * 【为什么第二次要用白卡，不用第一次那个深色卡】
 *
 * 第一次的卡是"提示"，贴在通话按钮上方，避开接听/挂断键；
 * 第二次的卡是"拦住她"，必须比通话界面更抢眼，所以用白底、大字、
 * 一块红色警示。位置也从贴顶改成屏幕上部居中——宁可压住号码，
 * 也不能压住下方的挂断键：她想挂电话时必须挂得掉。
 *
 * 【为什么不再有"15 分钟免打扰"】
 *
 * 原来点一次「认识」，同一个号码 15 分钟内不再问。
 * 这个设计被否掉是有道理的：号码看着眼熟，接起来未必是那个人。
 * 15 分钟足够骗子把话说完。
 * 现在「认识」只代表"这一次我认了"，防护一分不减，
 * 通话满 5 分钟照样再问一次。
 *
 * 【铁律】Judge.java 一行不改。熟人标记不进入判定，分数一分不减。
 * ============================================================
 */
public class Acquaint {

    private static final String TAG = "YinDun";

    /** 没人理会在多少毫秒后自动收起（只用于响铃卡；复问卡不许自动收） */
    private static final long AUTO_HIDE_MS = 20000;

    /** 复问卡：多久没人理就把振动降频（降频，不是停） */
    private static final long IN_CALL_SLOW_MS = 3 * 60 * 1000L;

    private static View view = null;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** 本次响铃已经问过了吗（一次响铃只问一次，别反复打扰） */
    private static boolean askedThisRing = false;
    private static String askedNumber = null;

    /** 当前这张卡是"通话中复问"吗（决定要不要停振动、要不要自动收） */
    private static boolean inCallCard = false;

    /**
     * 复问卡"已经决定要弹、但还没弹上去"的瞬间。
     *
     * 【为什么需要它】
     * askInCall 是通过 handler.post 到主线程才真正建窗口的，
     * 而同一轮 tick 里紧接着就会跑 evaluate()，那时 view 还是 null。
     * 结果黄条浮条判定"复问卡不存在"先弹了出来，
     * 等复问卡上来就把它压掉一半——实测截图上顶部露出一条黄边。
     * 提前立个旗子，浮条就不会抢在前面。
     */
    private static boolean pendingInCall = false;

    /* ==========================================================
     *  让"正在振动"这件事在屏幕上看得见
     * ==========================================================
     * 振动是真发出去的（dumpsys vibrator 里能看到 Waveform[0,400,1200] 循环），
     * 但模拟器没有马达、投影仪也传不出手感 —— 演示时全场只看到一张静止的卡，
     * 会以为我们根本没振。
     *
     * 所以卡片自己也跟着振动节奏抖：400ms 抖完一轮、静 1200ms，
     * 和 Vibrate.LOOP 的 {0,400,1200} 完全对齐。真假两条通道同步，看着才是一回事。
     * ========================================================== */
    private static View shakeTarget = null;
    private static int shakeFrame = 0;
    private static final int[] SHAKE_DP = {0, -3, 3, -2, 2, -1, 1, 0};

    private static final Runnable shakeStep = new Runnable() {
        @Override
        public void run() {
            View v = shakeTarget;
            if (v == null) {
                shakeFrame = 0;
                return;
            }
            if (shakeFrame >= SHAKE_DP.length) {
                v.setTranslationX(0);
                shakeFrame = 0;
                handler.postDelayed(this, 1200);   // 与"停 1200ms"对齐
                return;
            }
            v.setTranslationX(dp(v.getContext(), SHAKE_DP[shakeFrame]));
            shakeFrame++;
            handler.postDelayed(this, 50);
        }
    };

    private static void startShake(View v) {
        stopShake();
        shakeTarget = v;
        shakeFrame = 0;
        handler.post(shakeStep);
    }

    private static void stopShake() {
        handler.removeCallbacks(shakeStep);
        if (shakeTarget != null) {
            shakeTarget.setTranslationX(0);
        }
        shakeTarget = null;
        shakeFrame = 0;
    }

    private static final Runnable autoHide = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "熟人询问 20 秒没人理，自动收起（不算他认识）");
            hide(false);
        }
    };

    /** 复问卡挂了三分钟还没人按：振动降频，但绝不停 */
    private static final Runnable slowDown = new Runnable() {
        @Override
        public void run() {
            Vibrate.slowDown();
            Log.w(TAG, "复问卡 3 分钟没人按，振动降频（仍然不停）");
        }
    };

    // ==========================================================
    //  一、对外入口
    // ==========================================================

    /**
     * 第一次询问：响铃时调用。满足条件才真的弹。
     *
     * @param who 号码身份，只有 stranger 才问；contact/service 直接跳过
     */
    public static void askIfNeeded(final Context ctx, final String number, final String who) {
        if (!NumberInfo.STRANGER.equals(who)) return;
        if (number == null || number.length() == 0) return;

        // 同一次响铃已经问过，不再打断
        if (askedThisRing && eq(number, askedNumber)) return;

        // 没有悬浮窗权限就不弹了。这里刻意不降级去打开 App——
        // 响铃时把 App 拽到前台会把来电界面顶掉，老人可能就找不到接听按钮了。
        if (!Shield.canOverlay(ctx)) {
            Log.w(TAG, "熟人询问跳过：没有悬浮窗权限");
            return;
        }

        askedThisRing = true;
        askedNumber = number;

        handler.post(new Runnable() {
            @Override
            public void run() {
                show(ctx.getApplicationContext(), number, false);
            }
        });
    }

    /**
     * 第二次询问：接通满 5 分钟时调用。
     *
     * 这一张是"必须被看见"的卡：
     *   - 持续振动，点按钮才停（Vibrate.loop 传 0 = 不设自动停）
     *   - 卡片不自动消失
     *   - 3 分钟后只降频，不停
     */
    public static void askInCall(final Context ctx, final String number) {
        if (view != null) return;   // 已经有一张卡在，别叠
        if (!Shield.canOverlay(ctx)) {
            Log.w(TAG, "通话复问跳过：没有悬浮窗权限");
            return;
        }
        pendingInCall = true;    // 旗子先立起来，挡掉同一轮里的浮条
        handler.post(new Runnable() {
            @Override
            public void run() {
                show(ctx.getApplicationContext(), number, true);
            }
        });
    }

    /** 接通或挂断时调用，把卡片撤掉 */
    public static void dismiss() {
        handler.removeCallbacks(autoHide);
        handler.removeCallbacks(slowDown);
        askedThisRing = false;
        askedNumber = null;
        hide(true);
    }

    public static boolean showing() {
        return view != null;
    }

    /**
     * 现在屏上是"第二次询问"那张卡吗。
     *
     * 轻提醒浮条（TipBar）要据此让位：两张卡都弹在最上面，
     * 叠在一起会互相压字。复问卡更要紧，浮条让开。
     */
    public static boolean inCallShowing() {
        return pendingInCall || (view != null && inCallCard);
    }

    // ==========================================================
    //  二、窗口
    // ==========================================================

    private static void show(Context ctx, final String number, final boolean inCall) {
        pendingInCall = false;
        if (view != null) return;

        try {
            inCallCard = inCall;
            view = inCall ? buildInCall(ctx, number) : buildRing(ctx, number);

            int type = (Build.VERSION.SDK_INT >= 26)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    // 【2026-11-05 修】原来给 MATCH_PARENT，实测右侧会露出 16dp 的下层界面，
                    // 卡片看着像没铺满。按屏幕物理像素给死宽度才是真的满宽。
                    ctx.getResources().getDisplayMetrics().widthPixels,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    // NOT_FOCUSABLE：不抢输入焦点，通话界面的按键照常工作
                    // NOT_TOUCH_MODAL：卡片以外的区域触摸透传下去（下方挂断键仍可点）
                    // SHOW_WHEN_LOCKED：锁屏时也显示
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;

            if (inCall) {
                // 复问卡：从屏幕最上方压下来。
                //
                // 【2026-11-08 实测调整】原来放在状态栏下 72dp，卡片底边正好
                // 切在"免提"白色圆按钮中间，截图上看像半个按钮浮在卡片下面，
                // 很像界面没对齐。往上提到状态栏下 16dp 以后，卡片整块压住头像区，
                // 底边落在所有按钮上方，画面干净；下方 400dp 全留给挂断键。
                lp.y = statusBarHeight(ctx) + dp(ctx, 16);
            } else {
                // 响铃卡：让开顶部来电横幅，原来 200dp 是实测出来的
                lp.y = statusBarHeight(ctx) + dp(ctx, 200);
            }

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                view = null;
                return;
            }
            wm.addView(view, lp);

            handler.removeCallbacks(autoHide);
            handler.removeCallbacks(slowDown);

            if (inCall) {
                Log.i(TAG, "通话复问卡已弹出：" + number + "（持续振动，点按钮才停）");
                Vibrate.loop(ctx, 0);                    // 0 = 不自动停
                handler.postDelayed(slowDown, IN_CALL_SLOW_MS);
            } else {
                Log.i(TAG, "熟人询问已弹出：" + number);
                handler.postDelayed(autoHide, AUTO_HIDE_MS);
            }

        } catch (Exception e) {
            Log.e(TAG, "询问卡弹出失败", e);
            view = null;
            inCallCard = false;
        }
    }

    /**
     * 撤掉。
     *
     * @param resetRing true = 接通/挂断主动撤，要允许下一次响铃重新问
     */
    private static void hide(boolean resetRing) {
        handler.removeCallbacks(autoHide);
        handler.removeCallbacks(slowDown);
        stopShake();      // 卡片撤了，抖动也必须停，否则下次弹出来会是歪的

        // 只有复问卡才接管振动。响铃卡从来不振，
        // 这里要是无脑 stop，会把盖屏那边的持续振动一起掐掉。
        if (inCallCard) Vibrate.stop();
        inCallCard = false;

        View v = view;
        view = null;
        if (v != null) {
            try {
                WindowManager wm = (WindowManager) v.getContext()
                        .getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) wm.removeView(v);
            } catch (Exception ignored) {
            }
        }
        if (resetRing) {
            askedThisRing = false;
            askedNumber = null;
        }
    }

    // ==========================================================
    //  三、第一次询问的卡（响铃，深色贴顶）
    // ==========================================================

    private static View buildRing(final Context ctx, final String number) {
        // 外层只留左右边距，卡片悬浮在来电界面中部，不挡下方的接听 / 挂断键
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(ctx, 28));
        root.setBackground(bg);
        root.setElevation(dp(ctx, 12));
        root.setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 18));
        wrap.addView(root, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------- 顶栏：银盾 · 来电识别 ｜ 陌生号码 ----------
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View mark = AlarmView.mark(ctx);
        head.addView(mark, new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));
        TextView brand = new TextView(ctx);
        brand.setText("银盾 · 来电识别");
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        brand.setTextColor(0xFF475467);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setIncludeFontPadding(false);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(0, dp(ctx, 28), 1f);
        pb.leftMargin = dp(ctx, 8);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(brand, pb);
        TextView pill = new TextView(ctx);
        pill.setText("非通讯录号码");
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pill.setTextColor(0xFFB54708);
        pill.setTypeface(null, android.graphics.Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setIncludeFontPadding(false);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFFFEF0C7);
        pbg.setCornerRadius(dp(ctx, 12));
        pill.setBackground(pbg);
        pill.setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0);
        head.addView(pill, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 26)));
        root.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 28)));

        // 【为什么每个 TextView 都写死高度】
        // 悬浮窗高度是 WRAP_CONTENT，里面的 TextView 再 WRAP_CONTENT，
        // 在部分机型/模拟器上会被量成 0 高（"卡片在、一个字都看不见"）。
        TextView t1 = addText(ctx, root, "来电号码不在\n您的通讯录中", 26, 0xFF0B1220, dp(ctx, 76));
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        t1.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t1.setLineSpacing(dp(ctx, 4), 1f);
        ((LinearLayout.LayoutParams) t1.getLayoutParams()).topMargin = dp(ctx, 12);

        // ---------- 号码信息块：号码 + 被举报次数（云端异步查） ----------
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(0xFFF3F5F8);
        ibg.setCornerRadius(dp(ctx, 16));
        info.setBackground(ibg);
        info.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        TextView num = addText(ctx, info, number == null || number.length() == 0 ? "号码未知" : NumberInfo.pretty(number), 20, 0xFF0B1220, dp(ctx, 28));
        num.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        num.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        num.setLetterSpacing(0.04f);
        final TextView rep = addText(ctx, info, "正在查询该号码的举报记录…", 16, 0xFF667085, dp(ctx, 24));
        rep.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pi = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pi.topMargin = dp(ctx, 10);
        root.addView(info, pi);
        lookupReports(ctx, number, rep);

        TextView q = addText(ctx, root, "您是否认识来电人？", 21, 0xFF0B1220, dp(ctx, 34));
        q.setTypeface(null, android.graphics.Typeface.BOLD);
        ((LinearLayout.LayoutParams) q.getLayoutParams()).topMargin = dp(ctx, 14);

        // ---------- 两个一模一样的按钮：不暗示哪个答案"更对" ----------
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pr = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 64));
        pr.topMargin = dp(ctx, 12);
        root.addView(row, pr);
        Button yes = mkRingBtn(ctx, "认识", true);
        Button no = mkRingBtn(ctx, "不认识", false);
        row.addView(yes, new LinearLayout.LayoutParams(0, dp(ctx, 64), 1f));
        row.addView(new View(ctx), new LinearLayout.LayoutParams(dp(ctx, 12), dp(ctx, 64)));
        row.addView(no, new LinearLayout.LayoutParams(0, dp(ctx, 64), 1f));

        yes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "老人点了「认识」（响铃）：" + number);
                hide(true);
                toast(ctx, "已记录。防护将持续，通话较长时会再次确认");
                // 【2026-11-07 改】不再写 15 分钟免打扰，只把这件事告诉子女。
                Notifier.pushKnown(ctx, number, "老人表示认识来电人",
                        "响铃时老人选择了「认识」。防护未撤销，通话超过 5 分钟将再次确认。\n"
                                + "建议您核实该号码。");
            }
        });

        no.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "老人点了「不认识」（响铃）：" + number);
                hide(true);
                toast(ctx, "已记录，银盾将持续关注本次通话");
            }
        });

        TextView foot = addText(ctx, root, "无论选择哪项，防护都将持续\n通话时间较长时会再次确认", 15, 0xFF667085, dp(ctx, 46));
        foot.setLineSpacing(dp(ctx, 3), 1f);
        ((LinearLayout.LayoutParams) foot.getLayoutParams()).topMargin = dp(ctx, 10);

        return wrap;
    }

    /** 去云端查这个号码被几个人举报过（号码信誉库），查到了改那一行字；查不到就说没有 */
    private static void lookupReports(final Context ctx, final String number, final TextView tv) {
        if (number == null || number.length() < 5) {
            tv.setText("来电号码已隐藏，请谨慎接听");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = -1;
                try {
                    org.json.JSONObject o = DrillNet.get(ctx, "/api/report/stats?number="
                            + java.net.URLEncoder.encode(number, "UTF-8"));
                    if (o != null && o.optBoolean("ok")) n = o.optInt("count", 0);
                } catch (Exception ignored) {
                }
                final int cnt = n;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cnt > 0) {
                            tv.setText("已有 " + cnt + " 位用户举报该号码");
                            tv.setTextColor(0xFFB42318);
                            tv.setTypeface(null, android.graphics.Typeface.BOLD);
                        } else if (cnt == 0) {
                            tv.setText("暂无该号码的举报记录");
                        } else {
                            tv.setText("暂时无法查询，请按陌生来电谨慎处理");
                        }
                    }
                });
            }
        }).start();
    }

    // ==========================================================
    //  四、第二次询问的卡（通话中，白底居中偏上）
    //    视觉按 2026-11-07 确认的设计稿 1:1 还原
    // ==========================================================

    private static View buildInCall(final Context ctx, final String number) {
        // 外层只负责左右留白，让卡片看起来是"浮"在通话界面上的
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(dp(ctx, 18));
        card.setBackground(cardBg);
        card.setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 18));
        wrap.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------- 首行：小标签 + 右侧「振动中」 ----------
        // 「振动中」不是装饰。投影和录屏传不出手感，这一行字是全场唯一
        // 能看见的"它真的在振"的证据；点按钮停振后这张卡就消失了，所以不用撤字。
        LinearLayout tagRow = new LinearLayout(ctx);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tag = new TextView(ctx);
        tag.setText("银盾 · 再确认一次");
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tag.setTextColor(0xFFB42318);
        tag.setGravity(Gravity.CENTER);
        tag.setIncludeFontPadding(false);
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setColor(0xFFFEF0EE);
        tagBg.setCornerRadius(dp(ctx, 6));
        tag.setBackground(tagBg);
        tagRow.addView(tag, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 24)));

        TextView vibHint = new TextView(ctx);
        vibHint.setText("振动提醒中 · 请选择后停止");
        vibHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        vibHint.setTextColor(0xFFD92D20);
        vibHint.setIncludeFontPadding(false);
        vibHint.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        LinearLayout.LayoutParams pVibHint = new LinearLayout.LayoutParams(
                0, dp(ctx, 24), 1f);
        vibHint.setPadding(0, 0, dp(ctx, 2), 0);
        tagRow.addView(vibHint, pVibHint);

        card.addView(tagRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 24)));

        // ---------- 标题 ----------
        TextView title = new TextView(ctx);
        title.setText("本次通话已超过 5 分钟");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        title.setTextColor(0xFF101828);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(false);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pTitle = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 34));
        pTitle.topMargin = dp(ctx, 12);
        card.addView(title, pTitle);

        // ---------- 问题 ----------
        TextView ask = new TextView(ctx);
        ask.setText("您是否认识对方？");
        ask.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        ask.setTextColor(0xFF101828);
        ask.setIncludeFontPadding(false);
        ask.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pAsk = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 30));
        pAsk.topMargin = dp(ctx, 18);
        card.addView(ask, pAsk);

        // ---------- 两个按钮：等分，白底灰边（不预设哪个是"正确答案"） ----------
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pRow = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 48));
        pRow.topMargin = dp(ctx, 12);
        card.addView(row, pRow);

        Button yes = mkInCallBtn(ctx, "认识");
        Button no = mkInCallBtn(ctx, "不认识");
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(ctx, 48), 1f);
        p1.rightMargin = dp(ctx, 10);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(ctx, 48), 1f);
        row.addView(yes, p1);
        row.addView(no, p2);

        // ---------- 红色警示块 ----------
        // 左边一道红竖条 + 浅红底，右侧圆角。
        // 这句话不管是"认识"还是"不认识"都要留在屏幕上——
        // 骗子最常利用的正是"我认识他，所以不用防"。
        LinearLayout warn = new LinearLayout(ctx);
        warn.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable warnBg = new GradientDrawable();
        warnBg.setColor(0xFFFFF5F4);
        // 左上、左下直角（贴红条），右上、右下圆角
        warnBg.setCornerRadii(new float[]{
                0, 0,
                dp(ctx, 11), dp(ctx, 11),
                dp(ctx, 11), dp(ctx, 11),
                0, 0});
        warn.setBackground(warnBg);

        View bar = new View(ctx);
        bar.setBackgroundColor(0xFFD92D20);
        warn.addView(bar, new LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 58)));

        TextView warnText = new TextView(ctx);
        warnText.setText("无论是否认识，涉及转账汇款请先与家人核实");
        // 【2026-11-08 实测调小】设计稿 19sp 在真机上正好多出一个字：
        // "…交易金" / "钱" 折成两行，第二行只有孤零零一个字，比不写还难看。
        // 17sp 一行放得下，视觉上仍是全屏第二大的字。
        warnText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        warnText.setTextColor(0xFFB42318);
        warnText.setIncludeFontPadding(false);
        warnText.setGravity(Gravity.CENTER_VERTICAL);
        warnText.setPadding(dp(ctx, 13), 0, dp(ctx, 10), 0);
        warn.addView(warnText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 58)));

        LinearLayout.LayoutParams pWarn = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 58));
        pWarn.topMargin = dp(ctx, 16);
        card.addView(warn, pWarn);

        // ---------- 按钮行为 ----------
        yes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "通话复问：老人点了「认识」：" + number);
                hide(false);
                toast(ctx, "已记下。谈钱的事，等接完女儿电话再说");
                Notifier.pushInCallAnswer(ctx, true, number);
                EventBus.pushAlert("watch", "通话满 5 分钟，老人说认识对方", "in_call_ask", 40);
            }
        });

        no.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "通话复问：老人点了「不认识」：" + number);
                hide(false);
                toast(ctx, "好的，正在叫女儿打给您");
                Notifier.pushInCallAnswer(ctx, false, number);
                EventBus.pushAlert("warn", "通话满 5 分钟，老人说不认识对方", "in_call_ask", 60);
                // 她自己说不认识，还聊了 5 分钟——这是最该叫人的时候
                Notifier.callChild(ctx);
            }
        });

        // 卡片跟着振动节奏抖：投影上没有手感，这是让观众"看见"振动的唯一办法
        startShake(card);

        return wrap;
    }

    // ==========================================================
    //  五、小工具
    // ==========================================================
    private static TextView addText(Context ctx, LinearLayout root, String text,
                                    int sp, int color, int heightPx) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        v.setMaxLines(2);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx);
        root.addView(v, p);
        return v;
    }

    /** 响铃卡的按钮：两个长得一模一样（深蓝描边），不诱导老人点「认识」 */
    private static Button mkRingBtn(Context ctx, String text, boolean unused) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setTextColor(0xFF0A1A33);
        b.setStateListAnimator(null);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(dp(ctx, 18));
        d.setStroke(dp(ctx, 2), 0xFF0A1A33);
        b.setBackground(d);
        return b;
    }

    /** 复问卡的按钮：两个长得一样，不暗示哪个答案"更对" */
    private static Button mkInCallBtn(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTextColor(0xFF344054);
        b.setAllCaps(false);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(dp(ctx, 11));
        d.setStroke(dp(ctx, 1), 0xFFD0D5DD);
        b.setBackground(d);
        return b;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    /** 状态栏高度（拿不到就退回 24dp，宁可让一点也不会被压住） */
    private static int statusBarHeight(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Exception ignored) {
        }
        return dp(ctx, 24);
    }

    private static void toast(final Context ctx, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static boolean eq(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}
