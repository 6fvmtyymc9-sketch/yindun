package com.yindun.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pangu —— 华为云盘古大模型调用器
 * ============================================================
 * 【它在这个系统里的位置】
 *   它不在"拦钱"的那条路上。拦钱靠本地规则，毫秒级、断网也能用。
 *   它站在旁边，干两件本地规则干不了的事：
 *
 *     1. 灰区复审 —— 本地规则拿不准的短信（30~45 分之间），请模型判一次
 *     2. 人话摘要 —— 把一堆专业事件，翻成一句子女看得懂的话
 *
 * 【为什么绝不能让模型参与实时拦截】
 *   走一次网络要 1~3 秒，网差就 10 秒，断网直接废。
 *   而盖屏这件事，晚 3 秒钱就转出去了。
 *   所以本类里所有方法都是"异步 + 超时 + 失败就放弃"，
 *   它挂了不影响主流程，只是少一条辅助信息。
 *
 * 【为什么用 HTTP 手写而不用 SDK】
 *   华为云 MaaS 提供的是 OpenAI 兼容接口，就是一次普通的 POST。
 *   引一个 SDK 会让 APK 从 57K 涨到几 MB，不值得。
 *
 * 【接口怎么配】
 *   默认值指向华为云 MaaS 国内站预置服务：
 *     https://api.modelarts-maas.com/v2/chat/completions
 *   但地址、Key、模型名全部存在 SharedPreferences 里，可以随时改。
 *   因为比赛方给的可能是"预置服务"，也可能是"专属部署"，地址不一样：
 *     预置服务  https://api.modelarts-maas.com/v2/chat/completions
 *     专属部署  https://maas-cn-southwest-2.modelarts-maas.com/v1/infers/{id}/v1/chat/completions
 *   两种都是 Bearer 鉴权 + 同一种请求体，所以同一份代码都能用。
 * ============================================================
 */
public class Pangu {

    private static final String TAG = "YinDun.AI";

    // ---------- 默认配置 ----------
    /** 华为云 ModelArts Studio（MaaS）国内站，OpenAI 兼容接口 */
    public static final String DEF_URL   = "https://api.modelarts-maas.com/v2/chat/completions";
    /**
     * 默认用 deepseek-v4.1-flash，不用盘古。
     * 原因：非思考型（2 秒出结果，不烧思考链）、比 glm-5.2 便宜 4~4.5 倍、
     * 中文语感和话术理解够用，输出 JSON 也很少跑偏（下一步要 parse，跑偏就整条链路断）。
     * 【选型定论 2026-10-29】不再改。
     *
     * 注意：换模型不等于换平台。glm-5.2 也是挂在华为云 MaaS 上的，
     * 照样算「使用华为云产品」，赛题要求不会丢。
     * 千万别为了换模型去调别家云厂商的接口，那才是真的丢分。
     *
     * 可换的型号（华为云 MaaS 在架，各有独立免费额度）：
     *   glm-5.2 / glm-5.3 / deepseek-v4.1-flash / kimi-k2.6 / qwen3-32b / openpangu-2.0-flash
     */
    public static final String DEF_MODEL = "deepseek-v4.1-flash";

    private static final String PREF = "yindun";

    /** 连接超时：5 秒。连不上就别耗着 */
    private static final int TIMEOUT_CONNECT = 5000;
    /** 读取超时：12 秒。盘古慢思考模型会先输出思考过程，给宽一点 */
    private static final int TIMEOUT_READ = 20000;

    /** 单线程就够。同一时刻不会有两条短信在复审 */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** 回调：成功给文字，失败给原因（原因要能直接显示给人看） */
    public interface Callback {
        void ok(String text);
        void fail(String reason);
    }

    // ==========================================================
    //  一、配置读写
    // ==========================================================
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String getUrl(Context c)   { return sp(c).getString("maas_url", DEF_URL); }
    public static String getKey(Context c)   { return sp(c).getString("maas_key", ""); }
    public static String getModel(Context c) { return sp(c).getString("maas_model", DEF_MODEL); }

    public static void setConfig(Context c, String url, String key, String model) {
        SharedPreferences.Editor e = sp(c).edit();
        if (url != null)   e.putString("maas_url", url.trim());
        if (key != null)   e.putString("maas_key", key.trim());
        if (model != null) e.putString("maas_model", model.trim());
        e.apply();
    }

    /** 配好了没有（有 Key 就算配了，地址有默认值） */
    public static boolean configured(Context c) {
        return getKey(c) != null && getKey(c).length() > 8;
    }

    // ==========================================================
    //  二、连通性自检 —— 这是老板最需要的那个方法
    // ==========================================================
    /**
     * 发一句"你好"，看能不能通。
     *
     * 【为什么这个方法是第一优先】
     * 因为现在还不知道华为云账号里模型服务开没开通。
     * 这个方法会把失败原因说得非常具体：
     *   401 → Key 无效或服务未开通
     *   403 → Key 没权限 / 欠费
     *   404 → 地址写错（预置服务和专属部署地址不一样）
     *   超时 → 网络不通 / 模型排队
     * 这样不用猜，看一眼就知道该去控制台点哪里。
     */
    public static void ping(final Context ctx, final Callback cb) {
        final String key = getKey(ctx);
        if (key == null || key.length() == 0) {
            cb.fail("还没填 API Key。请到华为云 ModelArts Studio →「系统管理与统计 → 应用接入」创建，拿到的就是一串 Key。");
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String reply = call(ctx, "你好", 32);
                    final String r = reply == null ? "" : reply.trim();
                    post(new Runnable() {
                        @Override public void run() {
                            cb.ok("通了。模型回答：" + (r.length() > 40 ? r.substring(0, 40) + "…" : r));
                        }
                    });
                } catch (final Exception e) {
                    post(new Runnable() {
                        @Override public void run() { cb.fail(explainError(e)); }
                    });
                }
            }
        });
    }

    // ==========================================================
    //  三、灰区短信复审
    // ==========================================================
    /**
     * 本地规则拿不准的短信，请模型判一次。
     *
     * 【什么算"拿不准"】
     * 本地打分落在 30~45 之间。低于 30 明显没事，高于 45 明显有事，
     * 中间这一段是灰色地带——比如"您有一笔退款待领取，请点击链接"。
     *
     * 【结果怎么用】
     * 只许升级，不许降级。
     *   模型说是诈骗 → 本地级别往上提，推给子女
     *   模型说不是   → 什么都不做（不能因为它一句"不是"就把已经报出去的警报撤了）
     * 这是安全产品的铁律：宁可多报，不可错放。
     *
     * @param body  短信正文
     * @param who   发送方身份 contact / service / stranger
     * @param score 本地分数（只用于日志和措辞）
     */
    public static void reviewSms(final Context ctx, final String body, final String who,
                                 final int score, final Callback cb) {
        if (!configured(ctx)) { cb.fail("未配置模型"); return; }
        if (body == null || body.length() == 0) { cb.fail("空短信"); return; }

        // 只送前 200 字。诈骗短信的关键话术都在前半段，送多了浪费 token
        final String cut = body.length() > 200 ? body.substring(0, 200) : body;

        final String sys =
                "你是反诈短信审核员。判断给定短信是不是诈骗短信。\n" +
                "严格只输出一个 JSON，不要任何解释文字：\n" +
                "{\"scam\":true 或 false, \"stage\":\"S0\"到\"S4\", \"reason\":\"不超过20字\"}\n" +
                "判定标准：\n" +
                "- 冒充公检法、要求转账到安全账户、要求报验证码、要求别告诉家人 → 是诈骗\n" +
                "- 冒充客服退款、要求点链接验证身份、要求下载App → 是诈骗\n" +
                "- 银行/平台发来的正常验证码、账单、取件码 → 不是诈骗\n" +
                "- 促销、优惠券、活动通知 → 不是诈骗\n" +
                "stage 含义：S1套近乎 S2吓唬 S3让你别告诉家人 S4要钱。不是诈骗填 S0。";

        final String user =
                "发送方身份：" + (who == null ? "未知" : who) +
                "\n短信正文：" + cut;

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String reply = chat(ctx, sys, user, 200);
                    final String r = reply;
                    post(new Runnable() {
                        @Override public void run() { cb.ok(r); }
                    });
                } catch (final Exception e) {
                    post(new Runnable() {
                        @Override public void run() { cb.fail(explainError(e)); }
                    });
                }
            }
        });
    }

    // ==========================================================
    //  四、给子女的"人话摘要"
    // ==========================================================
    /**
     * 把一串专业事件翻成一句人话。
     *
     * 输入示例：
     *   "陌生号码来电, 通话 18 分钟, 打开手机银行, 收到银行验证码"
     * 输出示例：
     *   "妈妈正在被冒充公检法的人引导转账，已经打开银行了"
     *
     * 【为什么这个功能有价值】
     * 子女收到四条通知，要自己拼出"发生了什么"。
     * 而人在开会、开车的时候，是拼不出来的。
     * 一句话摘要，是她唯一会看完的东西。
     */
    public static void explain(final Context ctx, final String events, final Callback cb) {
        if (!configured(ctx)) { cb.fail("未配置模型"); return; }
        if (events == null || events.length() == 0) { cb.fail("无事件"); return; }

        final String sys =
                "你在给一位老人的子女写提醒。根据手机上的事件，用一句不超过30字的普通话说明可能正在发生什么。\n" +
                "要求：\n" +
                "- 说人话，不用「判据」「置信度」「S3阶段」这类词\n" +
                "- 不要吓人，不要夸张，只描述事实和最可能的情况\n" +
                "- 只输出那一句话，不要引号，不要解释";

        final String user = "手机上的事件（按时间顺序）：" + events;

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String reply = chat(ctx, sys, user, 120);
                    final String r = reply;
                    post(new Runnable() {
                        @Override public void run() { cb.ok(r); }
                    });
                } catch (final Exception e) {
                    post(new Runnable() {
                        @Override public void run() { cb.fail(explainError(e)); }
                    });
                }
            }
        });
    }

    // ==========================================================
    //  五、真正发请求
    // ==========================================================

    /** 简版：只有一句用户话 */
    private static String call(Context ctx, String userText, int maxTokens) throws Exception {
        return chat(ctx, "你是一个简洁的助手。", userText, maxTokens);
    }

    /**
     * 发一次 chat/completions 请求。
     *
     * 请求体就是 OpenAI 的标准格式：
     *   {"model":"...","messages":[{"role":"system",...},{"role":"user",...}],"max_tokens":N}
     * 华为云 MaaS 兼容这个格式，所以不用为它单独写适配。
     */
    private static String chat(Context ctx, String sys, String user, int maxTokens) throws Exception {
        String urlStr = getUrl(ctx);
        String key    = getKey(ctx);
        String model  = getModel(ctx);

        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray msgs = new JSONArray();
        JSONObject m1 = new JSONObject();
        m1.put("role", "system");
        // /no_think 是盘古快慢思考模型的控制标签：
        // 不加它会先输出一大段"思考过程"，多花 3~5 秒，而且 JSON 会被污染。
        // 但它只对盘古系有效，塞给 glm / deepseek 只会变成 prompt 里的垃圾字符，
        // 所以这里按模型名判断，换模型后自动跳过。
        String sysText = sys;
        if (model != null && model.toLowerCase().contains("pangu")) {
            sysText = sys + "\n/no_think";
        }
        m1.put("content", sysText);
        msgs.put(m1);
        JSONObject m2 = new JSONObject();
        m2.put("role", "user");
        m2.put("content", user);
        msgs.put(m2);
        body.put("messages", msgs);
        body.put("max_tokens", maxTokens);
        // 判定类任务要稳定，不要发挥。0 最稳
        body.put("temperature", 0);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_CONNECT);
            conn.setReadTimeout(TIMEOUT_READ);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(is);
            Log.i(TAG, "HTTP " + code + " 长度=" + text.length());

            if (code < 200 || code >= 300) {
                throw new HttpError(code, text);
            }
            return parseContent(text);

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从返回 JSON 里抠出模型说的话 */
    private static String parseContent(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("返回里没有 choices，原始内容：" + shorten(json));
        }
        JSONObject c0 = choices.getJSONObject(0);
        JSONObject msg = c0.optJSONObject("message");
        if (msg == null) throw new Exception("返回里没有 message");
        return msg.optString("content", "");
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    // ==========================================================
    //  六、错误翻译成人话
    // ==========================================================
    /** 带 HTTP 状态码的异常 */
    static class HttpError extends Exception {
        final int code;
        final String body;
        HttpError(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }

    /**
     * 把技术错误翻成"该去控制台点哪里"。
     *
     * 这段是本类最实用的部分。因为现在还不知道账号开没开通，
     * 报错越具体，老板越少踩坑。
     */
    static String explainError(Exception e) {
        if (e instanceof HttpError) {
            HttpError h = (HttpError) e;
            if (h.code == 401) return "401：API Key 不对，或这个服务还没开通。去 ModelArts Studio 检查 Key 是否复制完整、模型是否已开通。";
            if (h.code == 403) return "403：Key 没权限，或者账号欠费了。";
            if (h.code == 404) return "404：地址或模型名写错了。走免费额度时，地址要抄「免费服务 → 调用说明 → OpenAI SDK」里那个，别用统一域名。";
            if (h.code == 429) return "429：调用太频繁，被限流了。等几秒再试。";
            if (h.code >= 500) return h.code + "：模型服务端出错，稍后重试。原文：" + shorten(h.body);
            return h.code + "：请求失败。原文：" + shorten(h.body);
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("timeout") || m.contains("Timeout") || m.contains("timed out")) {
            return "超时：网络不通，或者模型在排队。检查手机能不能上外网。";
        }
        if (m.contains("Unable to resolve host") || m.contains("UnknownHost")) {
            return "域名解析失败：手机没有网络，或者地址写错了。";
        }
        if (m.contains("JSON")) {
            return "返回内容不是预期的 JSON，可能模型回答跑偏了。原文：" + shorten(m);
        }
        return "调用失败：" + shorten(m);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replace("\n", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static void post(Runnable r) {
        UI.post(r);
    }

    // ==========================================================
    //  七、给 Guard 用的小工具：把事件拼成一句话
    // ==========================================================
    /**
     * 把状态快照翻成"事件串"，喂给 explain()。
     * 之所以不直接传 JSON：模型读自然语言比读 JSON 准。
     */
    public static String eventsOf(Judge.State st, String extra) {
        StringBuilder sb = new StringBuilder();
        if (st.callWho != null) {
            sb.append("接到").append("stranger".equals(st.callWho) ? "陌生号码" : st.callWho).append("来电");
            if (st.callDur > 0) sb.append("，已通话").append(st.callDur / 60).append("分钟");
        }
        if (st.bankForeground) sb.append("，打开了手机银行");
        if (st.codeInCall)     sb.append("，收到银行验证码短信");
        if (st.unknownInstall) sb.append("，安装了非应用商店的软件");
        if (st.a11yGranted)    sb.append("，给那个软件开了无障碍权限");
        if (st.rejectFamily > 0) sb.append("，这期间拒接了家人").append(st.rejectFamily).append("次来电");
        if (extra != null && extra.length() > 0) sb.append("，").append(extra);
        return sb.toString();
    }
}
