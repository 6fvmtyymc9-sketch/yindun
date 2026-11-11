package com.yindun.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

    /**
     * 每天最多送多少条短信去大模型复核。
     * 正常老人一天到不了这个数；这道的用途是挡住短信轰炸，不是省钱省到抠门。
     */
    private static final int AI_REVIEW_PER_DAY = 120;

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
                + " 分数=" + r.score + " 阶段=" + r.stage + " 级别=" + r.level
                // 端侧模型分：-1 表示这台机器上模型缺席（架构不支持/加载失败），不是"判了0分"
                + " 模型分=" + (r.modelScore < 0 ? "缺席" : String.format(java.util.Locale.CHINA, "%.4f", r.modelScore))
                + " 命中=" + r.why);

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

        // ---------- 7. 大模型复核：本地拿不准的，交给华为云 MaaS 判一次 ----------
        //
        // 【2026-11-10 改：门限从"30~45 分灰区"放宽到"本地没到 warn"】
        //
        // 起因：留出集 20 条诈骗漏报 12 条（60%），分数几乎清一色 15 分——
        //   只命中"陌生号码"这一个特征。按原来的 30~45 门限，它们连复核的门都进不去。
        //   也就是说：规则在"换一种说法"的诈骗面前是瞎的，模型不该跟着一起瞎。
        //
        // 放宽之前先量过一遍（cloud/test/ai-review.js，同一份 prompt、同一批样本）：
        //   诈骗 12 条 → 模型抓回 12 条（100%）
        //   正常  9 条 → 误判  1 条（11.1%）
        //   误判的是「奶奶好，我是您孙女的同学，她让我跟您说今晚晚点回家」——
        //   这条本身也是"陌生号码 + 冒充熟人"，提醒一句不算冤枉。
        //   没有这组数就放宽门限，唯一的确定结果是 MaaS 账单变大。
        //
        // 【现在的门限】非服务号 + 本地未到 warn + （陌生号码 或 正文 ≥15 字）
        //   · 排除服务号：银行、运营商、快递的正常短信全在低分段，送模型纯烧钱，
        //     而且模型偶尔误判会把正常短信变成警报。
        //   · 加"正文够长"：冒充亲友、投资理财不一定用陌生号，但一定啰嗦；
        //     15 字以内多是验证码、取件码、问候。
        //
        // 【费用】单条约 0.0006 元。按每天 30 条进复核算，一天 2 分钱。
        //   上方还有一道每日次数闸（AI_REVIEW_PER_DAY），防的是短信轰炸把账单打爆。
        //
        // 【关键：只升级，不降级】
        //   模型说"是诈骗" → 升级推给子女
        //   模型说"不是"   → 什么都不做
        //   绝不能因为模型一句"不是"，就把本地已经报出去的警报撤回来。
        //   这是安全产品的铁律：宁可多报，不可错放。
        if (shouldReview(ctx, who, r, text)) {
            aiReview(ctx.getApplicationContext(), text, who, r.score);
        }
    }

    /**
     * 这条短信要不要送大模型复核。
     *
     * 门限是量出来的，不是拍的 —— 见上面第 7 步注释里那组数字。
     * 改这里之前先跑 `node cloud/test/ai-review.js holdout.json`，
     * 拿新的抓回率/误报率说话。
     */
    private boolean shouldReview(Context ctx, String who, Judge.Result r, String text) {
        // 服务号：银行/运营商/快递的正常短信都在这儿，送了纯烧钱
        if (NumberInfo.SERVICE.equals(who)) return false;

        // 本地已经判到 warn 及以上：警报早发出去了，模型再判一次也不会改变什么
        boolean belowWarn = "none".equals(r.level) || "watch".equals(r.level);
        if (!belowWarn) return false;

        boolean stranger   = NumberInfo.STRANGER.equals(who);
        boolean longEnough = text != null && text.length() >= 15;
        if (!stranger && !longEnough) return false;

        return aiQuota(ctx);
    }

    /**
     * 每日复核次数闸。
     *
     * 【为什么需要】复核是手机直接连 MaaS，云端那套 IP 限流管不到它。
     *   万一遇到短信轰炸（一条一条灌进来），账单就会跟着短信一起涨。
     *   120 次远高于正常老人一天的量，但足以挡住异常。
     *
     * 【为什么计数器坏了要放行】宁可多花几毛钱，也不能因为读不到 SharedPreferences
     *   就把整条 AI 复核悄悄关掉 —— 那等于功能没了还看不出来。
     */
    private boolean aiQuota(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("yindun", Context.MODE_PRIVATE);
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
            String day = sp.getString("aiReviewDay", "");
            int n = today.equals(day) ? sp.getInt("aiReviewCount", 0) : 0;
            if (n >= AI_REVIEW_PER_DAY) {
                Log.w(TAG, "今日 AI 复核次数已用完（" + n + "），本条只走本地规则");
                return false;
            }
            sp.edit().putString("aiReviewDay", today).putInt("aiReviewCount", n + 1).apply();
            return true;
        } catch (Exception e) {
            return true;
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
