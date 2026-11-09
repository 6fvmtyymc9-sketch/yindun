package com.yindun.guard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shield —— 阻断级：把老人的屏幕整个盖住
 * ============================================================
 * 【它是整个产品最狠的一招，也是最容易被做错的一招】
 *
 * 三个硬要求，一个都不能改：
 *
 * 1. **没有关闭按钮。**
 *    老人此刻正被电话那头操控。如果红屏上有关闭按钮，她会点——
 *    因为骗子早就教过她："那是干扰软件，一会儿弹出来你关掉它。"
 *
 *    但让老人"别接女儿的电话"，骗子的成本极高。
 *    他可以轻松说"点继续""关掉它"，却很难说"别接你闺女电话"。
 *
 *    我们不是在跟骗子的技术斗，是在跟他的人话术成本斗。
 *
 * 2. **唯一出口是"打给女儿"。**
 *    老人不需要做判断，只需要做一件事：按下去，然后听到女儿的声音。
 *
 * 3. **6 秒没人动，机器自己拨。**
 *    人会被吓懵，机器不会。
 *
 * 【技术上的实话】
 * 第三方 App 挂不断骗子的电话，安卓不给这个权限。
 * 所以"拦截"只能做到：盖住屏幕、抢走注意力、把女儿的声音叫过来。
 * 这一点在答辩时要说在前面，别吹。
 * ============================================================
 */
public class Shield {

    private static final String TAG = "YinDun";
    /** 无人操作多少毫秒后自动拨号 */
    private static final long AUTO_DIAL_MS = 6000;

    /** 兜底撤屏：2 分钟。没有任何正常情形需要在屏幕上挂超过 2 分钟 */
    private static final long SAFETY_HIDE_MS = 120000;

    private static View view = null;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean dialed = false;

    // ==========================================================
    //  振动可视化：既然手机在振，屏幕就该跟着晃
    // ==========================================================
    //
    // 【为什么非做不可】
    // 模拟器没有振动马达，真机上"振动"这件事在投影/录屏里是完全看不见的。
    // 答辩现场如果只靠嘴说"它在振"，说服力是零。
    // 所以把振动翻译成视觉语言：整块屏幕按振动的节奏左右晃。
    //
    // 【节奏必须和真机一致】
    // 真机是振 400ms、停 1200ms（见 Vibrate.LOOP）。
    // 这里的抖动窗口也做 400ms，间隔也 1200ms ——
    // 这样观众看到的"咚咚咚…停…咚咚咚"，就是老人手心里的感受，不是随便抖两下。
    //
    // 【为什么要越抖越小】
    // 每一"振"开头最猛、末尾收住，像真的振动在马达起转/停转时的衰减。
    // 匀速抖看起来像卡顿掉帧，反而显得是 bug。
    private static final Handler shakeHandler = new Handler(Looper.getMainLooper());
    private static boolean shaking = false;
    private static int shakeTick = 0;
    /** 一摆间隔（ms） */
    private static final int SHAKE_TICK_MS = 55;
    /** 一"振"摆几下：7 × 55 ≈ 385ms，对齐真机 400ms */
    private static final int SHAKE_TICKS = 7;
    /** 两次振之间停多久 */
    // 【2026-11-07 改】原来对齐真机的 1200ms。
    // 但真机是马达在振、屏幕不一定要跟着停那么久；停在屏幕上的表现就是"看着像静止的画面"，
    // 连拍十张有七张一样。改成 300ms，振动 385ms / 停 300ms，
    // 观感是"一直在抖"，同时真机马达节奏仍由 Vibrate 那一路控制，不受这里影响。
    private static final int SHAKE_PAUSE_MS = 300;

    // 【踩过的坑】一开始用 view.setTranslationX() 抖，一条都看不出来：
    // 悬浮窗的根 View 由 WindowManager 每次遍历时重新 layout，
    // translation 会被重置，等于白抖。必须改窗口自身的 x 坐标。
    // 改 x 会让窗口边缘移出屏幕、露出下面的 App，所以窗口要比屏幕宽出一圈，
    // 抖动时在这个余量里左右滑，永远盖满。
    private static WindowManager wmRef = null;
    private static WindowManager.LayoutParams lpRef = null;
    private static int shakeBaseX = 0;
    private static float shakeAmpPx = 0;
    /** 真正被左右推的，是这一层（内容层），不是窗口 */
    private static View shakeTarget = null;

    private static final Runnable shakeLoop = new Runnable() {
        @Override
        public void run() {
            if (!shaking || view == null || wmRef == null || lpRef == null) return;
            float k = 1f - (shakeTick / (float) SHAKE_TICKS) * 0.5f;  // 越往后越小
            float off = (shakeTick % 2 == 0 ? shakeAmpPx : -shakeAmpPx) * k;
            try {
                // 【2026-11-07 改】抖的是内容层，不是窗口。
                // 窗口一屏宽、红底不动，文字和按钮在里面左右滑，
                // 视觉上就是"整块屏在晃"，而且边缘永远被红底盖住，不会露底。
                if (shakeTarget != null) shakeTarget.setTranslationX(off);
            } catch (Exception e) {
                shaking = false;   // 窗口没了就别再抖
                return;
            }
            shakeTick++;
            if (shakeTick >= SHAKE_TICKS) {
                shakeTick = 0;
                try {
                    if (shakeTarget != null) shakeTarget.setTranslationX(0);   // 停顿期间回正
                } catch (Exception ignored) {
                }
                shakeHandler.postDelayed(shakeLoop, SHAKE_PAUSE_MS);
            } else {
                shakeHandler.postDelayed(shakeLoop, SHAKE_TICK_MS);
            }
        }
    };

    private static void shakeStart() {
        if (view == null || wmRef == null || lpRef == null) return;
        shaking = true;
        shakeTick = 0;
        shakeHandler.post(shakeLoop);
        Log.i(TAG, "屏幕抖动已开始（与振动同节奏：400ms 振 / 1200ms 停）");
    }

    private static void shakeStop() {
        shaking = false;
        shakeTick = 0;
        if (shakeTarget != null) {
            try { shakeTarget.setTranslationX(0); } catch (Exception ignored) {}
        }
        shakeHandler.removeCallbacks(shakeLoop);
        if (view != null && wmRef != null && lpRef != null) {
            try {
                lpRef.x = shakeBaseX;
                wmRef.updateViewLayout(view, lpRef);
            } catch (Exception ignored) {
            }
        }
    }

    /** 6 秒到点自动拨 */
    private static final Runnable autoDial = new Runnable() {
        @Override
        public void run() {
            if (view == null || dialed) return;
            Log.i(TAG, "6 秒无人操作，自动拨给子女");
            dialNow(view.getContext(), true);
        }
    };

    /** 按钮按下后 2.5 秒把"正在拨号…"改成拨出结果，别让老人盯着一个假状态 */
    private static final Runnable dialDone = new Runnable() {
        @Override
        public void run() {
            updateButtonText("已拨给女儿，等接听");
        }
    };

    /** 兜底撤屏（2 分钟到点自己走） */
    private static final Runnable safetyHide = new Runnable() {
        @Override
        public void run() {
            if (view == null) return;
            Log.w(TAG, "盖屏挂了 2 分钟，兜底撤掉（避免手机被永久占住）");
            hide(view.getContext());
        }
    };

    /**
     * 真拨号。自动拨和手动按都走这里。
     *
     * 【为什么单独抽一个方法】
     * 以前自动拨和手动按各写一遍，结果只改了一处文案，
     * 屏幕上就出现"正在拨给女儿…"挂了十分钟不动——电话根本没拨。
     */
    private static void dialNow(Context ctx, boolean auto) {
        dialed = true;
        handler.removeCallbacks(autoDial);
        handler.removeCallbacks(dialDone);
        Notifier.callChild(ctx);      // 没绑定号码也会拨演示号码，绝不空转
        if (auto) {
            updateButtonText("已拨给女儿，等接听");
        } else {
            updateButtonText("正在拨号");
            handler.postDelayed(dialDone, 2500);
        }
    }

    /**
     * 盖屏。
     *
     * @param title  一句话标题，比如"先别转钱"
     * @param number 骗子号码（只用于显示，让她知道是这通电话有问题）
     */
    public static void show(Context ctx, String title, String number) {
        if (view != null) {
            // 已经盖着了。不重复盖，也不动文字（避免把"已拨出"刷回"打给女儿"）
            return;
        }

        // ---------- 检查有没有悬浮窗权限 ----------
        if (!canOverlay(ctx)) {
            // 没权限：退一步，把 App 自己拽到前台（老人能看到，但要按 Home 就没了）
            Log.w(TAG, "没有悬浮窗权限，降级为打开 App 界面");
            try {
                Intent i = new Intent(ctx, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra("force_shield", true);
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
            return;
        }

        try {
            view = build(ctx, title, number);

            int type = (Build.VERSION.SDK_INT >= 26)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            // 窗口做得比屏幕宽一圈（左右各 14dp）。
            // 抖动是靠改窗口 x 坐标实现的，窗口要是正好和屏幕一样宽，
            // 一动就会在边上露出下面的 App。留出余量，它就在这圈余量里滑，
            // 怎么晃都盖满整屏。
            // 【2026-11-07 改】原来把窗口做得比屏幕宽一圈（sideMargin），
            // 想让它在余量里左右滑。实测 WindowManager 会把悬浮窗宽度卡在屏幕宽，
            // 多出来的部分直接被裁掉——结果抖动时右边永远露出一条底层 App 的竖边，
            // 看着像界面做坏了。改成窗口正好一屏宽，抖的是内容层（见 shakeLoop）。
            int sideMargin = 0;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ctx.getResources().getDisplayMetrics().widthPixels + sideMargin * 2,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    // 关键：这些 flag 让它盖在最上层，且抢焦点（抢焦点才能挡住下面的输入）
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN        // 盖掉状态栏
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    android.graphics.PixelFormat.OPAQUE);

            // 盖掉底部导航栏。
            // 【为什么要较真这一条】之前盖屏只盖了内容区，底部还留着白色的三键导航条，
            // 老人一眼就能看到"返回"，一按就回桌面——等于给了她一条逃跑路线。
            // 而且视觉上红屏缺一块，看着像做坏了。
            view.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            // 窗口正好一屏，不需要偏移
            lp.x = 0;

            wm.addView(view, lp);

            // 抖动要用的三样东西：窗口、窗口参数、抖动基准位
            wmRef = wm;
            lpRef = lp;
            shakeBaseX = 0;
            shakeAmpPx = dp(ctx, 10);

            Log.i(TAG, "屏幕已盖住");

            // 振动跟盖屏同时开始，而且一直振下去——
            // 不熄屏亮屏地分情况，只要盖住就振，振到有人按"停止振动"为止。
            // 理由见 Vibrate.java 顶部：响一遍就停，老人只会觉得"刚才震了一下"。
            Vibrate.loop(ctx);

            // 屏幕跟着一起晃：真机上这是"振动"的视觉投影，
            // 模拟器/无马达设备上，这是唯一能让人看见"它在振"的方式。
            shakeStart();

            // 播女儿的声音（预录，存本地，断网也能响）
            Speak.play(ctx, Speak.STOP);

            // 6 秒无人操作自动拨
            dialed = false;
            handler.postDelayed(autoDial, AUTO_DIAL_MS);

            // 兜底：万一电话一直没挂（或系统没回调），2 分钟后自己撤。
            // 屏幕永远退不掉比不盖屏更糟——那是把老人的手机变成了砖头。
            handler.postDelayed(safetyHide, SAFETY_HIDE_MS);

        } catch (Exception e) {
            Log.e(TAG, "盖屏失败", e);
            view = null;
        }
    }

    /** 撤掉盖屏（子女接通后由界面调用） */
    public static void hide(Context ctx) {
        handler.removeCallbacks(autoDial);
        handler.removeCallbacks(dialDone);
        handler.removeCallbacks(safetyHide);
        // 屏幕撤了还继续振（还继续晃），就是纯骚扰了
        Vibrate.stop();
        shakeStop();
        if (view == null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(view);
        } catch (Exception ignored) {
        }
        view = null;
        dialed = false;
        wmRef = null;      // 窗口已经没了，别让抖动还拿着引用
        lpRef = null;
        Log.i(TAG, "盖屏已撤");
    }

    public static boolean showing() {
        return view != null;
    }

    public static boolean canOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(ctx);
        }
        return true;
    }

    // ==========================================================
    //  界面（用代码画，不写 XML —— 少一个文件少一处出错）
    // ==========================================================
    private static View build(final Context ctx, String title, String number) {
        // ---------- 根：全屏红色 ----------
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFE23B3B, 0xFFC7000B});
        root.setBackground(bg);
        // 窗口本身比屏幕宽 28dp（抖动余量），内容得把这份宽度还回去，
        // 否则按钮会被拉宽、贴到屏幕边上。
        int pad = dp(ctx, 28);
        root.setPadding(pad, dp(ctx, 48), pad, dp(ctx, 40));

        // ---------- 顶部警示徽标 ----------
        // 【2026-11-07 改】原来是 🛡 emoji。
        // emoji 走的是系统的彩色字体，setTextColor 对它无效，
        // 在红底上渲染成暗橙棕色——一块红屏上糊着一个几乎看不见的图标，
        // 看着像界面做坏了，而不是"有个盾在保护你"。
        // 换成白色圆环 + "!"，颜色和粗细 100% 可控。
        TextView icon = new TextView(ctx);
        icon.setText("!");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        icon.setTextColor(0xFFFFFFFF);
        icon.setTypeface(null, android.graphics.Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x24FFFFFF);
        ring.setStroke(dp(ctx, 3), 0xFFFFFFFF);
        icon.setBackground(ring);
        LinearLayout.LayoutParams pIcon =
                new LinearLayout.LayoutParams(dp(ctx, 108), dp(ctx, 108));
        root.addView(icon, pIcon);

        // ---------- 主标题 ----------
        TextView t1 = new TextView(ctx);
        t1.setText(title == null ? "先别操作" : title);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        t1.setTextColor(Color.WHITE);
        t1.setGravity(Gravity.CENTER);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p1.topMargin = dp(ctx, 16);
        root.addView(t1, p1);

        // ---------- 副标题 ----------
        // 【2026-11-07 改】原来是"女儿正在打你电话"。
        // 这句是写死的：拨号可能失败、可能没绑号码走的演示号，
        // 一旦失败，屏幕上就在告诉老人一件没发生的事。
        // 改成"这通电话可能是骗子"（说原因）+ "正在叫女儿打给您"（说我们在做什么）。
        // 前者是这一屏存在的理由——老人最需要知道的不是"谁来电话了"，
        // 而是"电话那头这个人是坏人"。之前整屏没有一个字说这件事。
        TextView t2 = new TextView(ctx);
        t2.setText("这通电话可能是骗子\n正在叫女儿打给您");
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        t2.setTextColor(0xFFFFE8E6);
        t2.setGravity(Gravity.CENTER);
        t2.setLineSpacing(dp(ctx, 10), 1f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p2.topMargin = dp(ctx, 20);
        root.addView(t2, p2);

        // ---------- 骗子号码 ----------
        // 【2026-11-07 改】原来只写"正在通话：xxx"——她当然知道正在通话。
        // 换成"不在通讯录里"，这才是这块屏想说的事：这个号她不认识。
        if (number != null && number.length() > 0) {
            TextView t3 = new TextView(ctx);
            t3.setText("对方号码 " + number + " · 不在通讯录里");
            t3.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t3.setTextColor(0xB3FFFFFF);
            t3.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p3.topMargin = dp(ctx, 16);
            root.addView(t3, p3);
        }

        // ---------- 唯一出口：打给女儿 ----------
        Button btn = new Button(ctx);
        btn.setTag("dial");          // updateButtonText 靠这个 tag 认人（屏上现在有两个按钮了）
        btn.setText("打给女儿");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        btn.setTextColor(0xFFB32711);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 78));
        pb.topMargin = dp(ctx, 40);
        root.addView(btn, pb);

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialNow(ctx, false);
            }
        });

        // ---------- 第二个出口：只停振动，不撤屏幕 ----------
        // 为什么要有这个按钮：
        // 振动是一直振不停的，如果没地方关，老人急起来会直接抠电池/长按关机，
        // 那保护就彻底丢了。给她一个"我知道，别响了"的按钮，
        // 她才愿意停下来看这块屏幕。而屏幕留着，就还留着她看到"打给女儿"的机会。
        final Button stopBtn = new Button(ctx);
        stopBtn.setTag("stop");
        stopBtn.setText("停止振动");
        stopBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        stopBtn.setTextColor(0xFFFFFFFF);
        // 【2026-11-07 改】原来是 0x3DFFFFFF 的半透明白底。
        // 在红色渐变上，这个色几乎化进背景，看着像个"禁用"按钮，
        // 老人不会想到它是可以按的。
        // 改成透明底 + 白色描边：仍然是次要动作（不跟"打给女儿"抢），
        // 但明确是"一个按钮"。
        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setColor(0x1FFFFFFF);
        stopBg.setCornerRadius(dp(ctx, 28));
        stopBg.setStroke(dp(ctx, 2), 0xE6FFFFFF);
        stopBtn.setBackground(stopBg);
        LinearLayout.LayoutParams ps = new LinearLayout.LayoutParams(
                dp(ctx, 210), dp(ctx, 56));
        ps.topMargin = dp(ctx, 22);
        root.addView(stopBtn, ps);

        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Vibrate.stop();
                // 振动停了，屏幕也得停 —— 否则画面还在晃，
                // 老人会以为关不掉，反而更慌。
                shakeStop();
                stopBtn.setText("已停止振动");
                stopBtn.setEnabled(false);
                stopBtn.setAlpha(0.5f);
                Log.i(TAG, "老人自己按了停止振动（屏幕提示保留）");
            }
        });

        // ---------- 底部小字 ----------
        // 【2026-11-07 改】原来写"🔒 屏幕关不掉，只能让振动停下来"。
        // 这句有两个毛病：
        //   1. "屏幕关不掉"是在告诉她手机被锁住了。老人第一反应是"手机坏了/中病毒了"，
        //      慌起来就去抠电池、长按电源键——保护反而断了。
        //   2. 它解释了机制，却没给行动。老人看完不知道下一步该干嘛。
        // 改成：先说清"这是有人在保护你"（安心），再给唯一的行动（等女儿电话）。
        TextView lock = new TextView(ctx);
        lock.setText("别担心，这是「银盾」在保护您\n转账的事，等接完女儿的电话再说");
        lock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        lock.setTextColor(0xE6FFFFFF);
        lock.setGravity(Gravity.CENTER);
        lock.setLineSpacing(dp(ctx, 7), 1f);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(ctx, 26);
        root.addView(lock, pl);

        // 外面再套一层：红底铺满整屏不动，里面这层才是被抖的内容。
        FrameLayout outer = new FrameLayout(ctx);
        outer.setBackground(bg);
        outer.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        shakeTarget = root;
        return outer;
    }

    /** 改按钮文字（自动拨号后告诉老人"已经在拨了"） */
    private static void updateButtonText(final String text) {
        if (view == null || text == null) return;
        view.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // 只改"打给女儿"那个。屏上现在还有一个"停止振动"的按钮，
                    // 按 tag 精确找，不会误改到它。
                    View b = view.findViewWithTag("dial");
                    if (b instanceof Button) ((Button) b).setText(text);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
