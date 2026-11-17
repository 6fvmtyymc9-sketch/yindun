package com.yindun.guard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * 挂断当前这通电话。
 *
 * 【为什么需要它】
 * 红屏是一层全屏悬浮窗，把系统通话界面上的挂断键整个盖住了。
 * 老人想挂掉骗子那通电话，手指摸不到任何按键 —— 只能等骗子自己挂，
 * 或者干等红屏 2 分钟兜底撤掉。这不是"防护严格"，是把她的手机变成了砖头。
 *
 * 【为什么出口是"挂断"而不是"退出红屏"】
 * 如果只做一个"关闭"按钮，撤掉红屏之后通话还在、支付宝还在前面，
 * 她照样能接着转账 —— 等于给骗子留了一条话术："屏幕上那个红色东西，点右下角就没了"。
 * 所以红屏上那个出口的动作，必须是挂断本身。电话断了，红屏自然撤（见 Guard 的通话结束回调）。
 *
 * 【技术前提】
 * Android 9（API 28）起系统提供了 TelecomManager.endCall()，但要 ANSWER_PHONE_CALLS 权限。
 * 这是普通应用能申请的危险权限，不需要系统签名、不需要 root。
 * 个别 ROM 可能只放行默认拨号器，所以这个方法一定会如实返回 false，绝不假装成功。
 */
public final class Hangup {

    private static final String TAG = "YinDun";

    private Hangup() {
    }

    /** 系统允不允许我们挂断一通不是自己拨的电话 */
    public static boolean permitted(Context ctx) {
        if (Build.VERSION.SDK_INT < 28) return false;   // endCall() 从 28 才有
        try {
            return ctx.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 现在是否真的处于通话中 */
    public static boolean inCall(Context ctx) {
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            return tm != null && tm.isInCall();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 请求挂断当前通话。
     *
     * @return true = 系统接受了挂断请求，通话随即结束
     *         false = 没挂成（没权限 / 系统只许默认拨号器挂 / 本来就没在通话）
     */
    public static boolean endCall(Context ctx) {
        if (Build.VERSION.SDK_INT < 28) {
            Log.w(TAG, "系统版本低于 Android 9，没有挂断接口");
            return false;
        }
        if (!permitted(ctx)) {
            Log.w(TAG, "没有 ANSWER_PHONE_CALLS 权限，挂不了这通电话");
            return false;
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm == null) return false;
            if (!tm.isInCall()) {
                Log.i(TAG, "现在不在通话中，不需要挂断");
                return false;
            }
            boolean ok = tm.endCall();
            Log.i(TAG, "TelecomManager.endCall() 返回 " + ok);
            return ok;
        } catch (SecurityException e) {
            // 这个 ROM 只让默认拨号器挂别人的电话
            Log.w(TAG, "系统拒绝挂断（该 ROM 可能只放行默认拨号器）", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "挂断调用失败", e);
            return false;
        }
    }
}
