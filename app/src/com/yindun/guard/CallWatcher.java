package com.yindun.guard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * CallWatcher —— 通话状态采集器。App 的第二只眼睛
 * ============================================================
 * 【它能知道什么】
 *   号码是谁（陌生/通讯录/服务号）、通话开始了吗、了多久、挂断了吗。
 *
 * 【它绝对不知道什么】
 *   通话内容。一个字都听不到。这不是我们没做，是安卓不给——
 *   从安卓 9 开始第三方 App 连录音权限都拿不到。
 *
 * 【那它有什么用】
 *   它是"时间"和"身份"的唯一来源。有了它，我们才能说：
 *   "这是陌生号码，已经聊了 8 分钟" —— 这句话本身就是警报。
 *
 *   而且它还负责发现一个关键动作：**通话中拒接了家人的来电**。
 *   骗子的经典话术是"别接别人电话，他们都是骗子"。
 *   老人拒接女儿的电话，这是骗局最真实的痕迹，而且绕不过去。
 *
 * 【每 5 秒看一次表】
 *   "通话满 5 分钟"这件事没有事件可等，只能主动看表。
 *   5 秒查一次，误差可以接受；1 秒查一次太耗电，老人手机耗电就会被卸载。
 * ============================================================
 */
public class CallWatcher {

    private static final String TAG = "YinDun";
    private static CallWatcher inst = null;

    /** 由 App 启动时调用一次 */
    public static synchronized void start(Context c) {
        if (inst == null) inst = new CallWatcher(c.getApplicationContext());
        inst.begin();
    }

    public static synchronized void stop() {
        if (inst == null) return;
        try {
            TelephonyManager tm = (TelephonyManager)
                    inst.ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && inst.listener != null) {
                tm.listen(inst.listener, PhoneStateListener.LISTEN_NONE);
            }
            inst.handler.removeCallbacks(inst.ticker);
        } catch (Exception ignored) {
        }
        inst.running = false;
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PhoneStateListener listener = null;
    private boolean running = false;
    private boolean inCall = false;

    private CallWatcher(Context c) {
        this.ctx = c;
    }

    /** 每 5 秒跑一次：看表 + 查有没有拒接家人 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                Guard.get(ctx).tick();
                if (inCall) checkRejectedFamily();
            } catch (Exception e) {
                Log.e(TAG, "tick 出错", e);
            }
            handler.postDelayed(this, 5000);
        }
    };

    private void begin() {
        if (running) return;
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            Log.e(TAG, "这台设备没有电话功能，通话监听跳过");
            return;
        }

        listener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                try {
                    handle(state, phoneNumber);
                } catch (Exception e) {
                    Log.e(TAG, "处理通话状态出错", e);
                }
            }
        };

        try {
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
            running = true;
            handler.postDelayed(ticker, 5000);
            Log.i(TAG, "通话监听已启动");
        } catch (Exception e) {
            Log.e(TAG, "启动通话监听失败（可能没给 READ_PHONE_STATE）", e);
        }
    }

    /**
     * 通话状态变了。
     *
     * 【号码为什么可能拿不到】
     * 安卓 9 以后，很多机型不再把来电号码传给第三方。
     * 所以拿不到时要去通话记录里捞最新一条兜底。
     *
     * 【为什么还要判断呼入还是呼出】
     * 老人自己拨出去的电话，说明她认识对方，不该报警。
     * 少了这一步，"给儿子打电话"也会被当成陌生号码长通话，误报一堆。
     */
    private void handle(int state, String phoneNumber) {
        String num = phoneNumber;
        int dir = 0;   // 1=呼入 2=呼出

        NumberInfo.Call c = NumberInfo.latestCall(ctx);
        if (c != null) {
            dir = c.type;
            if (num == null || num.length() == 0) num = c.number;
        }

        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            // ---------- 通话中 ----------
            inCall = true;

            // 【2026-11-12 修】银盾自己拨给家人的那通，必须按"呼出"算。
            // 刚接通这一瞬间通话记录还没落盘，上面查到的 dir 是空的；
            // 而家人号码不一定存在通讯录里（演示号码就一定不在），
            // 于是 classify 判成 stranger —— 银行又正好在前台，
            // 「通话中打开银行」这条阻断判据就成立了，明明是我们自己打的电话。
            // 详见 Notifier.selfDialed() 上面那段实测记录。
            if (dir != 2 && Notifier.selfDialed(num)) {
                dir = 2;
                Log.i(TAG, "这通电话是银盾自己拨出的（" + num + "），按呼出处理，不当作陌生来电");
            }

            String who = NumberInfo.classify(ctx, num);
            if (dir == 2) who = NumberInfo.CONTACT;   // 老人自己打的，不报警

            Guard g = Guard.get(ctx);
            g.st.callDirection = (dir == 2) ? 2 : 1;
            g.onCallState(2, num, who);

        } else if (state == TelephonyManager.CALL_STATE_RINGING) {
            // ---------- 响铃 ----------
            Stats.call(ctx);
            Guard.get(ctx).onCallState(1, num, NumberInfo.classify(ctx, num));

        } else {
            // ---------- 挂断 ----------
            inCall = false;
            Guard.get(ctx).onCallState(0, num, null);
        }
    }

    /**
     * 查这次通话期间，有没有拒接家人的来电。
     *
     * 只查一次（Guard 里用 rejectFamily==0 挡住重复）。
     * 查到了就把状态翻成 1，然后重新跑一遍判定。
     */
    private void checkRejectedFamily() {
        Guard g = Guard.get(ctx);
        if (g.callStartAt == 0) return;
        if (g.st.rejectFamily > 0) return;

        if (NumberInfo.hasRejectedFamilyCall(ctx, g.callStartAt)) {
            g.st.rejectFamily = 1;
            Log.i(TAG, "发现：通话中拒接了家人的来电");
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("type", "call");
                o.put("state", "rejectFamily");
                o.put("dur", g.st.callDur);
                EventBus.push(o.toString());
            } catch (Exception ignored) {
            }
            g.tick();   // 状态变了，重新判一次
        }
    }
}
