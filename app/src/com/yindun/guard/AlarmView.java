package com.yindun.guard;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * AlarmView —— 红屏的"警报系统"动效
 * ============================================================
 * 效果：深红底 + 细扫描线、四周呼吸红光、下移的扫描光带、上下边框流光，
 *       中央防护罩（HUD 角标、刻度圆、雷达扫描、三道冲击波、发光盾牌）。
 *
 * 【性能怎么保证】（模拟器是软件模拟显卡，全屏逐帧重算会卡到 1~2 秒一帧）
 *   · 全屏的东西只画一次：底色、扫描线、红晕都是画好的静态图
 *   · 全屏的"动"只用图层属性：红晕改透明度、扫描光带和流光改位移 —— 不重画像素
 *   · 每帧真正重画的只有中央 250dp 的防护罩（Core）
 *
 * 【节奏】所有闪动都慢于每秒 1 次：老人眼睛受不了频闪，也避免光敏不适。
 * 系统关了动画（开发者选项 / 省电模式）就只显示静态的一帧。
 * ============================================================
 */
public class AlarmView {

    /** 系统是否允许动画 */
    static boolean motion(Context ctx) {
        try {
            return Settings.Global.getFloat(ctx.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception e) {
            return true;
        }
    }

    /** 全屏背景层：静态底图 + 呼吸红晕 + 扫描光带 + 边框流光。加在红屏最底下。 */
    static void addBackground(Context ctx, FrameLayout outer) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        boolean mo = motion(ctx);

        outer.addView(new Still(ctx), new FrameLayout.LayoutParams(-1, -1));

        View vig = new Vignette(ctx);
        vig.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        outer.addView(vig, new FrameLayout.LayoutParams(-1, -1));
        if (mo) loop(ObjectAnimator.ofFloat(vig, "alpha", 0.35f, 0.9f), 800, true);
        else vig.setAlpha(0.6f);

        int H = ctx.getResources().getDisplayMetrics().heightPixels;
        View band = new View(ctx);
        band.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00FF7864, 0x1AFF7864, 0x38FFA08C}));
        outer.addView(band, new FrameLayout.LayoutParams(-1, (int) (140 * d)));
        if (mo) loop(ObjectAnimator.ofFloat(band, "translationY", -140 * d, H + 20 * d), 3600, false);
        else band.setVisibility(View.GONE);

        int W = ctx.getResources().getDisplayMetrics().widthPixels;
        for (int i = 0; i < 2; i++) {
            View e = new View(ctx);
            e.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x00FFD6D0, 0xFFFFD6D0, 0x00FFD6D0}));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (140 * d), (int) (2 * d));
            lp.gravity = i == 0 ? android.view.Gravity.TOP : android.view.Gravity.BOTTOM;
            outer.addView(e, lp);
            if (mo) {
                ObjectAnimator a = ObjectAnimator.ofFloat(e, "translationX", -140 * d, W + 140 * d);
                a.setStartDelay(i * 1100);
                loop(a, 2200, false);
            } else {
                e.setVisibility(View.GONE);
            }
        }
    }

    /** 按钮外一圈向外扩散的光：放在按钮正下方（同一个 FrameLayout 里） */
    static View callPulse(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        View v = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(24 * d);
        g.setStroke((int) (2 * d), 0xFFFFFFFF);
        v.setBackground(g);
        if (motion(ctx)) {
            loop(ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.1f), 1600, false);
            loop(ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.45f), 1600, false);
            loop(ObjectAnimator.ofFloat(v, "alpha", 0.6f, 0f), 1600, false);
        } else {
            v.setAlpha(0f);
        }
        return v;
    }

    private static void loop(ObjectAnimator a, long ms, boolean reverse) {
        a.setDuration(ms);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(reverse ? ValueAnimator.REVERSE : ValueAnimator.RESTART);
        if (!reverse) a.setInterpolator(new LinearInterpolator());
        a.start();
    }

    // ==========================================================
    //  静态底图：深红径向底色 + 细扫描线 + 上下边框底线（只画一次）
    // ==========================================================
    static class Still extends View {
        private Bitmap bmp;

        Still(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
                float d = getResources().getDisplayMetrics().density;
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas b = new Canvas(bmp);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setShader(new RadialGradient(w * 0.5f, h * 0.3f, Math.max(w, h) * 0.85f,
                        new int[]{0xFF9E1C15, 0xFF5E0C08, 0xFF240302}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                b.drawRect(0, 0, w, h, p);
                p.setShader(null);
                p.setColor(0x07FFFFFF);
                for (float y = 0; y < h; y += 4 * d) b.drawRect(0, y, w, y + d * 0.6f, p);
                p.setColor(0x73FF5A46);
                b.drawRect(0, 0, w, 2 * d, p);
                b.drawRect(0, h - 2 * d, w, h, p);
            }
            c.drawBitmap(bmp, 0, 0, null);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (bmp != null) { bmp.recycle(); bmp = null; }
        }
    }

    /** 四周一圈红晕（静态图，呼吸靠改透明度） */
    static class Vignette extends View {
        private final Paint p = new Paint();

        Vignette(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            p.setShader(new RadialGradient(w * 0.5f, h * 0.45f, Math.max(w, h) * 0.75f,
                    new int[]{0x00FF281E, 0x00FF281E, 0x6BFF3223}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
    }

    // ==========================================================
    //  中央防护罩：唯一一块逐帧重画的区域（250dp 见方）
    // ==========================================================
    static class Core extends View {
        private final float d;
        private final boolean mo;
        private final long t0 = System.currentTimeMillis();
        private boolean running;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shield = new Path();
        private final RectF tmp = new RectF();
        private Bitmap still;
        private Shader sweep, glow, core;
        private float cx, cy;

        Core(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            mo = motion(c);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            running = mo;
            if (running) postInvalidateOnAnimation();
        }

        @Override
        protected void onDetachedFromWindow() {
            running = false;
            if (still != null) { still.recycle(); still = null; }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            if (still == null) build(w, h);
            float t = mo ? (System.currentTimeMillis() - t0) / 1000f : 0f;
            float R = 105 * d;

            c.drawBitmap(still, 0, 0, null);

            // 雷达扫描（3s 一圈）
            c.save();
            c.rotate((t % 3f) / 3f * 360f, cx, cy);
            p.setStyle(Paint.Style.FILL);
            p.setShader(sweep);
            c.drawCircle(cx, cy, R, p);
            p.setShader(null);
            c.restore();

            // 三道冲击波（2.4s，错开 0.8s）
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            for (int i = 0; i < 3; i++) {
                float k = ((t + i * 0.8f) % 2.4f) / 2.4f;
                p.setColor(argb((int) (190 * (1 - k)), 255, 130, 110));
                c.drawCircle(cx, cy, 58 * d * (0.55f + 1.45f * k), p);
            }

            // 核心：外发光随 1.6s 呼吸
            float rc = 50 * d;
            p.setStyle(Paint.Style.FILL);
            p.setShader(glow);
            p.setAlpha((int) (255 * (0.5f + 0.5f * wave(t, 1.6f))));
            c.drawCircle(cx, cy, rc * 1.9f, p);
            p.setAlpha(255);
            p.setShader(core);
            c.drawCircle(cx, cy, rc, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0xD9FFBEB4);
            c.drawCircle(cx, cy, rc, p);

            // 盾牌 + ×
            float s = 52 * d / 24f, ox = cx - 12 * s, oy = cy - 12 * s;
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(1.9f * s);
            c.drawPath(shield, p);
            c.drawLine(ox + 9.5f * s, oy + 9.5f * s, ox + 14.5f * s, oy + 14.5f * s, p);
            c.drawLine(ox + 14.5f * s, oy + 9.5f * s, ox + 9.5f * s, oy + 14.5f * s, p);

            if (running) postInvalidateDelayed(40);   // 约 25 帧
        }

        private void build(int w, int h) {
            cx = w / 2f;
            cy = h / 2f;
            float R = 105 * d, box = Math.min(w, h) / 2f - d, L = 22 * d, rc = 50 * d;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);

            sweep = new SweepGradient(cx, cy, new int[]{0x00FF5A46, 0x00FF5A46, 0x61FF6E5A, 0x99FFC8BE},
                    new float[]{0f, 0.8f, 0.995f, 1f});
            glow = new RadialGradient(cx, cy, rc * 1.9f, new int[]{0xDCFF503C, 0x00FF503C}, null, Shader.TileMode.CLAMP);
            core = new RadialGradient(cx, cy - rc * 0.2f, rc, new int[]{0x73FF7864, 0xD9780A06}, null, Shader.TileMode.CLAMP);

            float s = 52 * d / 24f, ox = cx - 12 * s, oy = cy - 12 * s;
            shield.reset();
            shield.moveTo(ox + 12 * s, oy + 3 * s);
            shield.lineTo(ox + 19 * s, oy + 6 * s);
            shield.lineTo(ox + 19 * s, oy + 11 * s);
            shield.cubicTo(ox + 19 * s, oy + 15.5f * s, ox + 16 * s, oy + 19.3f * s, ox + 12 * s, oy + 21 * s);
            shield.cubicTo(ox + 8 * s, oy + 19.3f * s, ox + 5 * s, oy + 15.5f * s, ox + 5 * s, oy + 11 * s);
            shield.lineTo(ox + 5 * s, oy + 6 * s);
            shield.close();

            // 不动的部分画一次：HUD 角标、刻度圆、十字准线、虚线内圈、外圈刻度
            still = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(still);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0xCCFFAA9B);
            corner(c, cx - box, cy - box, L, L);
            corner(c, cx + box, cy - box, -L, L);
            corner(c, cx - box, cy + box, L, -L);
            corner(c, cx + box, cy + box, -L, -L);
            p.setStrokeWidth(d);
            p.setColor(0x47FF9687);
            c.drawCircle(cx, cy, R, p);
            p.setColor(0x29FF9687);
            c.drawLine(cx, cy - R, cx, cy + R, p);
            c.drawLine(cx - R, cy, cx + R, cy, p);
            p.setColor(0x59FF9687);
            float r2 = 69 * d;
            tmp.set(cx - r2, cy - r2, cx + r2, cy + r2);
            for (int i = 0; i < 48; i += 2) c.drawArc(tmp, i * 7.5f, 4.5f, false, p);
            p.setColor(0x66FFB4A8);
            for (int i = 0; i < 60; i++) {
                double a = Math.toRadians(i * 6);
                float in = (i % 5 == 0) ? R - 9 * d : R - 5 * d;
                c.drawLine(cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in,
                        cx + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R, p);
            }
        }

        private void corner(Canvas c, float x, float y, float dx, float dy) {
            c.drawLine(x, y, x + dx, y, p);
            c.drawLine(x, y, x, y + dy, p);
        }
    }

    /** 银盾标志：深蓝圆角方块 + 浅蓝线条盾牌（来电卡、提醒卡的抬头用） */
    static View mark(Context ctx) {
        return new View(ctx) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();
            private final Path sh = new Path();

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF0A1A33);
                r.set(0, 0, w, h);
                c.drawRoundRect(r, w * 0.32f, w * 0.32f, p);
                float s = w * 0.6f / 24f, ox = (w - 24 * s) / 2f, oy = (h - 24 * s) / 2f;
                sh.reset();
                sh.moveTo(ox + 12 * s, oy + 3 * s);
                sh.lineTo(ox + 19 * s, oy + 6 * s);
                sh.lineTo(ox + 19 * s, oy + 11 * s);
                sh.cubicTo(ox + 19 * s, oy + 15.5f * s, ox + 16 * s, oy + 19.3f * s, ox + 12 * s, oy + 21 * s);
                sh.cubicTo(ox + 8 * s, oy + 19.3f * s, ox + 5 * s, oy + 15.5f * s, ox + 5 * s, oy + 11 * s);
                sh.lineTo(ox + 5 * s, oy + 6 * s);
                sh.close();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * s);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(0xFF7FB0FF);
                c.drawPath(sh, p);
            }
        };
    }

    /** 0→1→0 的平滑波形，period 秒一周 */
    static float wave(float t, float period) {
        return (float) (0.5 - 0.5 * Math.cos((t % period) / period * Math.PI * 2));
    }

    static int argb(int a, int r, int g, int b) {
        return (Math.max(0, Math.min(255, a)) << 24) | (r << 16) | (g << 8) | b;
    }
}
