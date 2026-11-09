package com.yindun.guard;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * Vibrate —— 触觉通道，也是最后一道能传到老人身上的通道
 * ============================================================
 * 【为什么振动比屏幕更可靠】
 *
 * 老人接起电话，把手机贴在耳朵上——距离感应器会把屏幕熄掉。
 * 从这一刻到通话结束，屏幕上不管盖着多大的红字，她**都看不见**。
 *
 * 听觉也不行：电话那头的人一直在说话，我们再播声音就是三个声音打架，
 * 老人听不出哪句该信（而且放太响会盖住骗子，她反而更慌）。
 *
 * 只剩触觉。手机在她手里，振动是唯一骗不掉、也挡不住的通道。
 * 骗子可以说"那是诈骗软件，关掉它"，但他没法让老人感觉不到手里的震动。
 *
 * 【为什么不响一遍就停，要一直振】
 *
 * 响一遍就停，老人只会觉得"刚才好像震了一下"，然后继续听电话。
 * 人的注意力不是被"一次刺激"打断的，是被"持续不适"打断的。
 * 所以这里的策略是：振起来就不停，一直振到她愿意看屏幕、按下"停止振动"。
 *
 * 这不是骚扰，这是把"决定权"还给她——
 * 她不用听懂红屏上写了什么，只要觉得烦，按下那个按钮，就会看到屏幕，
 * 屏幕上只有一条路：打给女儿。
 *
 * 【必须有停止按钮，而且只能手动停】
 *
 * 不许自动停。自动停等于告诉她"忍一会儿就过去了"，
 * 老人最擅长的就是忍。
 *
 * 但工程上要有保险：万一悬浮窗没权限、盖屏没弹出来，
 * 手机就会一直振到没电，而她找不到地方关。所以设了 SAFETY_MS 兜底，
 * 两分钟仍无人处理就自己停（屏幕提示和自动拨号不受影响）。
 * ============================================================
 */
public class Vibrate {

    private static final String TAG = "YinDun";

    /**
     * 循环模式：{启动延迟, 振, 停}
     *
     * 振 400ms、停 1200ms —— 大约 1.6 秒"敲"一下，像有人在外面轻轻拍门。
     *
     * 为什么不是连着振：
     * 连续振动的感觉是"手机坏了"，老人的第一反应是抠电池、长按关机，
     * 那保护就彻底丢了。留出停顿，听起来才像"有人在叫你"，而不是机器故障。
     *
     * 为什么停顿不放到 3 秒以上：
     * 间隔太长，老人会以为是错觉、或者习惯了就无视掉。
     * 1.5~2 秒是"还在叫，但没在折磨你"的那个区间。
     */
    private static final long[] LOOP = {0, 400, 1200};

    /**
     * 降频节奏：振 250ms，停 4800ms。
     *
     * 【为什么需要它】
     * 复问卡要求"不点按钮就一直振"，但没人理的时候一直 1.6 秒敲一下，
     * 三分钟后老人只会想长按关机。所以三分钟后退到"5 秒轻敲一次"：
     * 音量降到不烦人，但始终在——他想起来的时候，手机还在叫他。
     */
    private static final long[] LOOP_SLOW = {0, 250, 4800};

    /** 提醒级：响一遍就停，不循环（这类不弹窗，不该一直烦人） */
    private static final long[] REMIND_LONG  = {0, 500, 200, 500};
    private static final long[] REMIND_SHORT = {0, 150, 120, 150};

    /** 保险时长：两分钟仍无人按"停止振动"，自己停，防止振到没电 */
    private static final long SAFETY_MS = 120_000;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Vibrator vib = null;

    private static final Runnable safetyStop = new Runnable() {
        @Override
        public void run() {
            if (vib == null) return;
            Log.w(TAG, "振动满两分钟没人关，自动停（屏幕提示仍然在）");
            stop();
        }
    };

    /**
     * 开始持续振动 —— 一直振，直到有人按"停止振动"，或者保险到期。
     *
     * 亮屏、息屏都振，不做区分：熄屏时本来就看不见屏幕，
     * 亮屏时她可能正盯着通话界面发呆，两种情况下振动都是同一件事——
     * 提醒她"先放下这通电话"。
     */
    public static void loop(Context ctx) {
        loop(ctx, SAFETY_MS);
    }

    /**
     * 同 loop，但可以自己指定保险时长。
     *
     * @param safetyMs 多少毫秒后自动停；传 0 表示不设保险（必须有人手动停）
     *
     * 【谁会传 0】
     * 通话中复问卡。用户要求"必须手动按按钮才停"，所以这里不能自己停；
     * 但也不能让它按 1.6 秒一下振到底——所以复问卡那边会在 3 分钟后调
     * slowDown()，把节奏放慢到 5 秒一下，仍然不停。
     */
    public static void loop(Context ctx, long safetyMs) {
        stop();   // 万一已经有一个在振，先掐掉，避免叠加

        try {
            vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) {
                Log.w(TAG, "这台设备没有振动马达，只能用屏幕提示");
                vib = null;
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                // repeat = 0：从第 0 个时间点开始无限重复整段节奏
                vib.vibrate(VibrationEffect.createWaveform(LOOP, 0));
            } else {
                vib.vibrate(LOOP, 0);
            }
            Log.i(TAG, "持续振动已开始（振 400 / 停 1200，循环）"
                    + (safetyMs > 0 ? "，最长 " + (safetyMs / 1000) + " 秒" : "，不自动停"));

            handler.removeCallbacks(safetyStop);
            if (safetyMs > 0) handler.postDelayed(safetyStop, safetyMs);
        } catch (Exception e) {
            // 手机设了"静音不振动"、或系统策略禁了振动，这里会抛。不是错误，只是没振成。
            Log.w(TAG, "振动没响：" + e.getMessage());
            vib = null;
        }
    }

    /**
     * 降频：把"1.6 秒敲一下"改成"5 秒敲一下"，但继续振。
     * 只改节奏，不改"停不停"——停还是只能靠人按按钮。
     */
    public static void slowDown() {
        if (vib == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(LOOP_SLOW, 0));
            } else {
                vib.vibrate(LOOP_SLOW, 0);
            }
            Log.i(TAG, "振动降频：改成 5 秒轻敲一次，仍然不停");
        } catch (Exception e) {
            Log.w(TAG, "降频失败：" + e.getMessage());
        }
    }

    /** 停止振动。按按钮、撤掉盖屏、保险到期，都走这里 */
    public static void stop() {
        handler.removeCallbacks(safetyStop);
        if (vib == null) return;
        try {
            vib.cancel();
            Log.i(TAG, "振动已停");
        } catch (Exception ignored) {
        }
        vib = null;
    }

    /** 现在还在振吗？（界面上用来决定按钮显示"停止振动"还是"已停止"） */
    public static boolean running() {
        return vib != null;
    }

    /**
     * 提醒级：响一遍就结束。
     * 用在"通话超时""装了远控软件"这类不弹窗、只想提醒一下的场景。
     */
    public static void remind(Context ctx, boolean screenOff) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] p = screenOff ? REMIND_LONG : REMIND_SHORT;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(p, -1));
            } else {
                v.vibrate(p, -1);
            }
        } catch (Exception e) {
            Log.w(TAG, "提醒振动没响：" + e.getMessage());
        }
    }

    /** 屏幕现在是黑的吗？（熄屏 = 她正贴着耳朵，看不见盖屏） */
    public static boolean screenOff(Context ctx) {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && !pm.isInteractive();
        } catch (Exception e) {
            return false;
        }
    }
}
