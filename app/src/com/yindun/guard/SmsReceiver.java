package com.yindun.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SmsReceiver —— 短信广播接收器。整个 App 的第一只眼睛
 * ============================================================
 * 【它是怎么被叫醒的】
 * 手机收到短信时，系统向全手机广播一条 SMS_RECEIVED。我们在清单文件里
 * 登记过"我要听这条"，所以系统会把我们叫起来。
 *
 * 就算 App 没打开、界面没启动，也能被叫醒 —— 这就是"自动检测"的关键。
 *
 * 【它拿到什么】
 *   发件号码、正文、到达时间。就这三样。
 *
 * 【它做什么】
 *   1. 判断号码身份（陌生/通讯录/服务号）
 *   2. 判断是不是验证码短信
 *   3. 把正文交给 Judge 打分
 *   4. 如果是"通话中收到的验证码"，立刻通知 Guard —— 这是阻断级的触发条件
 *
 *   ★ 它不负责"定什么是诈骗"。那是 rules.json 的事（A 线）。
 *     这里只负责"发现 + 分类 + 上报"。规则改了不用重新编译 APK。
 * ============================================================
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "YinDun";

    /** 验证码类短信用词。命中任意一个，再看有没有数字 */
    private static final String[] CODE_WORDS = {
            "验证码", "校验码", "动态码", "确认码", "授权码", "验证代码",
            "verification code", "code is", "otp"
    };

    /** 连续 4~8 位数字。验证码一般是这个长度 */
    private static final Pattern DIGITS = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        try {
            handle(ctx, intent);
        } catch (Exception e) {
            Log.e(TAG, "处理短信出错", e);
        }
    }

    private void handle(Context ctx, Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        // pdus 是原始短信包。一条长短信会被切成几段，所以要遍历拼接。
        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) return;

        String format = bundle.getString("format");   // 3gpp / 3gpp2

        StringBuilder body = new StringBuilder();
        String from = null;
        long time = 0;

        for (Object p : pdus) {
            SmsMessage sms;
            try {
                sms = (format != null)
                        ? SmsMessage.createFromPdu((byte[]) p, format)
                        : SmsMessage.createFromPdu((byte[]) p);
            } catch (Exception e) {
                continue;     // 某段解析失败就跳过，别让整条短信丢掉
            }
            if (sms == null) continue;

            if (from == null) from = sms.getOriginatingAddress();
            body.append(sms.getMessageBody());
            if (time == 0) time = sms.getTimestampMillis();
        }

        if (from == null) return;
        String text = body.toString();

        // ---------- 1. 这个号是谁 ----------
        String who = NumberInfo.classify(ctx, from);

        // ---------- 2. 是不是验证码 ----------
        boolean isCode = looksLikeCode(text);

        // ---------- 3. 交给判定引擎打分 ----------
        Judge.Result r = Judge.get(ctx).judgeSms(text, who, isCode);
        Stats.sms(ctx);
        Log.i(TAG, "短信 from=" + from + " who=" + who + " 验证码=" + isCode
                + " 分数=" + r.score + " 阶段=" + r.stage + " 级别=" + r.level);

        // ---------- 4. 推给界面（判据看板 + 子女端卡片） ----------
        try {
            JSONObject o = new JSONObject();
            o.put("type", "sms");
            o.put("from", from);
            o.put("body", text);
            o.put("who", who);
            o.put("isCode", isCode);
            o.put("time", time);
            o.put("clock", new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(time)));
            o.put("stage", r.stage);
            o.put("level", r.level);
            o.put("score", r.score);
            o.put("why", r.why);
            EventBus.push(o.toString());
        } catch (Exception ignored) {
        }

        // 分数够高就当成一条预警推给子女端
        if (!"none".equals(r.level) && !"watch".equals(r.level)) {
            EventBus.pushAlert(r.level, r.why, r.stage, r.score);
            Notifier.alertChild(ctx, r.level,
                    isCode ? "妈妈收到一条验证码短信" : "妈妈收到可疑短信",
                    "来自 " + from + "：" + r.why);
        }

        // ---------- 5. 关键一步：通话中收到的验证码 ----------
        // 这是"阻断级"的触发条件之一。骗子正在电话里要这个数字，
        // 我们比她开口更早拿到。只要把它遮住，她念不出来。
        Guard g = Guard.get(ctx);
        if (isCode && g.callStartAt > 0 && NumberInfo.STRANGER.equals(g.st.callWho)) {
            Log.w(TAG, "★ 通话中收到验证码 —— 阻断级");
            g.onCodeInCall();
            // 通知界面：让老人端立刻盖屏（网页里已有盖屏逻辑）
            EventBus.pushAlert("block", "通话中收到验证码", "S4", 100);
        }

        // ---------- 6. 老人能看见的提示 ----------
        // 【2026-11-09 改】原来这里只弹 Toast：在屏幕下方、3 秒就没，
        // 老人低头看短信时正好被系统短信界面挡住，等于没提醒。
        // 改成和微信那条同一套浮条 —— 盖在屏幕最上层，她点开短信它还在。
        //
        // 为什么只对"高危诈骗"和"验证码"弹：
        // 每来一条短信都弹，老人会烦，然后去关权限。宁可少弹，不能烦人。
        if (isCode) {
            alert(ctx, "warn", "收到验证码短信", "请勿向任何人透露验证码");
        } else if (!"none".equals(r.level) && !"watch".equals(r.level)) {
            alert(ctx, r.level, "疑似诈骗短信", "请勿点击短信中的链接");
        }

        // ---------- 7. 灰区复审：本地拿不准的，交给华为云盘古判一次 ----------
        //
        // 【什么是灰区】本地分数 30~45 之间。
        //   低于 30 明显没事，高于 45 明显有事，只有中间这一小段谁都说不准。
        //
        // 【为什么正好是这一段最需要模型】
        //   因为"营销短信"和"钓鱼短信"在这一段长得最像。
        //   比如"您有一笔退款待领取，请点击链接"——本地规则只能看词，
        //   模型能看出"这话是在骗你点链接"。
        //
        // 【为什么服务号例外】
        //   银行、运营商的正常短信全在这一段，送模型纯属浪费钱，
        //   而且模型的偶尔误判会把正常短信搞成警报。
        //
        // 【关键：只升级，不降级】
        //   模型说"是诈骗" → 升级推给子女
        //   模型说"不是"   → 什么都不做
        //   绝不能因为模型一句"不是"，就把本地已经报出去的警报撤回来。
        //   这是安全产品的铁律：宁可多报，不可错放。
        if (r.score >= 30 && r.score < 45 && !NumberInfo.SERVICE.equals(who)) {
            aiReview(ctx.getApplicationContext(), text, who, r.score);
        }
    }

    // ==========================================================
    //  老人端提示：优先浮条，没权限才退回 Toast
    // ==========================================================
    private void alert(final Context ctx, final String level,
                       final String title, final String sub) {
        // 【为什么从下方弹】短信到达时，系统自己也会在顶部弹一条通知横幅
        // （Google Messages 那条带 Reply 按钮的会挂十几秒）。我们的浮条如果也在顶部，
        // 就被整条压住——实测截图上一个字都看不见，等于没提醒。
        // 改成贴屏幕下方，两边互不干扰：上面是"来短信了"，下面是"这条是诈骗"。
        //
        // 【为什么等 1.2 秒】让系统通知先落地，老人先意识到"有短信"，
        // 再看到判词，顺序才符合真实认知；同一瞬间两条一起冒出来反而糊。
        final Context app = ctx.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (Shield.canOverlay(app)) {
                    TipBar.showBottom(app, level, title, sub);
                } else {
                    // 悬浮窗权限被关掉时的兜底。效果差，但总比一声不响强。
                    Toast.makeText(app, title + " —— " + sub, Toast.LENGTH_LONG).show();
                }
            }
        }, 1200);
    }

    // ==========================================================
    //  华为云盘古大模型：灰区复审
    // ==========================================================
    private void aiReview(final Context ctx, final String body,
                          final String who, final int score) {
        Pangu.reviewSms(ctx, body, who, score, new Pangu.Callback() {
            @Override
            public void ok(String reply) {
                try {
                    JSONObject o = new JSONObject(extractJson(reply));
                    boolean scam  = o.optBoolean("scam", false);
                    String stage  = o.optString("stage", "S0");
                    String reason = o.optString("reason", "");

                    Log.i(TAG, "AI复审 scam=" + scam + " stage=" + stage + " reason=" + reason);

                    if (!scam) {
                        // 判为正常：只在内部留个记录，不打扰任何人
                        EventBus.pushAI("review", "AI 判为正常：" + reason, true);
                        return;
                    }

                    EventBus.pushAI("review", "AI 确认可疑：" + reason, true);
                    EventBus.pushAlert("warn", "AI 复审：" + reason, stage, score + 20);
                    Notifier.alertChild(ctx, "warn",
                            "妈妈收到一条可疑短信",
                            "AI 复核判定为可疑：" + reason
                                    + "。原文：" + brief(body));

                } catch (Exception e) {
                    // 模型返回的不是预期 JSON（可能输出跑偏），当没复审过
                    Log.w(TAG, "AI复审返回解析失败：" + reply);
                }
            }

            @Override
            public void fail(String reason) {
                // 复审失败不影响任何主流程，只记一笔
                Log.w(TAG, "AI复审失败：" + reason);
                EventBus.pushAI("review", reason, false);
            }
        });
    }

    /** 模型有时会把 JSON 包在 ```json 里，或者前后带解释，这里只抠出花括号那一段 */
    private String extractJson(String s) {
        if (s == null) return "{}";
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) return s.substring(a, b + 1);
        return s;
    }

    private String brief(String s) {
        if (s == null) return "";
        s = s.replace("\n", " ").trim();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    /**
     * 这条短信像不像验证码。
     *
     * 两个条件同时满足才算：
     *   ① 有"验证码/校验码/动态码"这类词
     *   ② 有一个 4~8 位连续数字
     *
     * 为什么不只看数字：手机号、订单号、金额也是长数字，单看数字误报太多。
     *
     * 注意：我们不提取那个数字本身。知道"有验证码"就够了，
     * 真去取数字，反而成了"我们也在读老人的验证码"，答辩时说不清。
     */
    private boolean looksLikeCode(String text) {
        if (text == null || text.length() == 0) return false;
        String lower = text.toLowerCase();

        boolean hasWord = false;
        for (String w : CODE_WORDS) {
            if (lower.contains(w.toLowerCase())) { hasWord = true; break; }
        }
        if (!hasWord) return false;

        Matcher m = DIGITS.matcher(text);
        return m.find();
    }
}
