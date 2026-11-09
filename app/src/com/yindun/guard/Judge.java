package com.yindun.guard;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Judge —— 判定引擎。整个 App 的"大脑"
 * ============================================================
 * 【它干什么】
 *   拿一条短信 / 一份当前状态，回答三个问题：
 *     1. 危险吗？（打分）
 *     2. 到哪一步了？（S0 无风险 / S1 套近乎 / S2 吓唬 / S3 隔离 / S4 要钱）
 *     3. 该不该动手？（无 / 关注 / 橙 / 红 / 阻断）
 *
 * 【为什么规则不写死在代码里】
 *   因为规则要改几十遍。写死在 Java 里，每改一个词就要重新编译、重新打包、
 *   重新装到手机上——改一次五分钟，一天改十次就不用干别的了。
 *   写进 rules.json，改完把文件换掉就生效。A 线随时能调，不用等 B 线。
 *
 *   这也是答辩时能讲的一个点：规则和引擎分离，规则可迭代、可测试。
 *
 * 【一个故意设计的"拦路"】
 *   加载规则前会检查里面有没有出现"拿不到的字段"（比如 call.content 通话内容）。
 *   只要出现，直接拒绝加载并报错。
 *
 *   为什么要这么狠：如果 A 线写了一堆"听到骗子说转账就报警"的规则，
 *   代码不报错、静默不生效，那这些规则会一直躺在文件里，谁都不知道它是死的。
 *   到演示现场才发现"这条怎么从来不触发"，就晚了。
 *   宁可现在就炸，炸给人看。
 * ============================================================
 */
public class Judge {

    /** 单例。整个 App 只用一份规则，不用每次判定都重新读文件。 */
    private static Judge inst = null;

    public static synchronized Judge get(Context ctx) {
        if (inst == null) inst = new Judge(ctx.getApplicationContext());
        return inst;
    }

    // ==========================================================
    //  一、永远拿不到的字段。规则里出现任何一个，拒绝加载。
    // ==========================================================
    private static final String[] FORBIDDEN = {
            "call.content",   // 通话内容
            "call.audio",     // 通话音频
            "call.record",    // 通话录音
            "wx.chat",        // 微信聊天正文
            "sms.code",       // 验证码数字本身（故意不取）
            "transcript",     // 转写文本
            "voice.print"     // 声纹
    };

    // ==========================================================
    //  二、银行 / 支付类 App 的识别特征
    // ==========================================================
    /**
     * 判断一个包名是不是"能转钱的 App"。
     *
     * 【为什么不用完整包名列表】
     * 各家银行的包名千奇百怪（com.icbc / com.chinamworld.main / com.ccb.longjiLife），
     * 列不全。而且老人手机上可能装的是小银行、村镇银行。
     *
     * 所以用"关键词包含"来判：包名或应用名里带这些词，就算。
     * 会多判（比如某个游戏叫 bank），但宁可多判——多判只是让老人多看一眼，
     * 漏判是钱没了。
     */
    private static final String[] BANK_WORDS = {
            "icbc", "ccb", "abc", "boc", "cmb", "bocom", "psbc", "ceb", "cib",
            "spdb", "cmbc", "citic", "pingan", "unionpay", "bank", "pay",
            "alipay", "tenpay", "wallet", "手机银行", "银行", "支付", "钱包"
    };

    // ==========================================================
    //  三、规则数据（从 rules.json 读出来，存在内存里）
    // ==========================================================
    /**
     * 链接特征：正文里有没有"可点的网址"。
     *
     * 两个分支：
     *   ① 带协议头的完整网址（http:// https:// www.）
     *   ② 不带协议头的短链（t.cn/xxx、bit.ly/xxx）——骗子最常用这种，
     *      因为它短、看着"官方"、在短信里能点开
     *
     * 只认形式，不认域名真假：核验域名要联网，短信到达那一秒来不及。
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:https?://|www\\.)[a-z0-9\\-._/?=&%#:@+~]+"
                    + "|\\b(?:t\\.cn|bit\\.ly|dwz\\.cn|url\\.cn|s\\.cn|w\\.cn|"
                    + "rb\\.gy|suo\\.im|6url\\.cn|tinyurl\\.com|j\\.mp)/[a-z0-9\\-._/?=&%#]+",
            Pattern.CASE_INSENSITIVE);

    /** 关键词：每个词带权重和它属于哪个阶段 */
    private final List<Word> words = new ArrayList<Word>();
    /** 反例词：命中就扣分，用来压误报 */
    private final List<Word> negatives = new ArrayList<Word>();
    /** 各阶段的门槛分 */
    private final int[] stageNeed = new int[5];   // S0..S4
    /** 各等级的门槛分 */
    private int lvWatch, lvWarn, lvHigh, lvBlock;
    /** 组合判据 */
    private final List<Combo> combos = new ArrayList<Combo>();
    /** 官方宣传短信免打扰配置（对应 rules.json 的 notice_alert） */
    private boolean noticeOn = false;
    private String noticeWho = "service";
    private int noticeMin = 2;
    private final List<String> noticeMarkers = new ArrayList<String>();
    private final List<String> noticeVeto = new ArrayList<String>();

    /** 规则文件有没有加载成功。没成功的话一切判定都不做，免得乱报。 */
    public boolean loaded = false;
    /** 加载失败的原因，界面上要显示出来（不藏错误） */
    public String loadError = null;

    /** 一个词条 */
    private static class Word {
        String text;
        int weight;
        String stage;   // S1..S4
    }

    /** 一条组合判据，例如：陌生号 + 通话中打开银行 → 阻断 */
    private static class Combo {
        List<Cond> conds = new ArrayList<Cond>();
        String level;   // watch / warn / high / block
        String why;     // 给子女看的一句话
    }

    /** 组合判据里的一个条件，例如 call.dur > 300 */
    private static class Cond {
        String field;
        String op;      // = > < >= <=
        String value;
    }

    // ==========================================================
    //  四、构造：读规则文件
    // ==========================================================
    private Judge(Context ctx) {
        try {
            String text = readAsset(ctx, "rules.json");
            JSONObject root = new JSONObject(text);

            // ---------- 第一步：先做禁止字段检查 ----------
            checkForbidden(root);

            // ---------- 第二步：读词库 ----------
            JSONArray ws = root.optJSONArray("sms_keywords");
            if (ws != null) {
                for (int i = 0; i < ws.length(); i++) {
                    JSONObject o = ws.getJSONObject(i);
                    Word w = new Word();
                    w.text = o.optString("word", "");
                    w.weight = o.optInt("weight", 0);
                    w.stage = o.optString("tag", "S1");
                    if (w.text.length() > 0) words.add(w);
                }
            }

            // ---------- 第三步：读反例词 ----------
            JSONArray ns = root.optJSONArray("sms_negative");
            if (ns != null) {
                for (int i = 0; i < ns.length(); i++) {
                    JSONObject o = ns.getJSONObject(i);
                    Word w = new Word();
                    w.text = o.optString("word", "");
                    w.weight = o.optInt("weight", 0);   // 这里是负数
                    if (w.text.length() > 0) negatives.add(w);
                }
            }

            // ---------- 第四步：读阶段门槛 ----------
            JSONObject st = root.getJSONObject("stages");
            for (int i = 0; i <= 4; i++) {
                JSONObject s = st.optJSONObject("S" + i);
                stageNeed[i] = (s == null) ? 0 : s.optInt("need", 0);
            }

            // ---------- 第五步：读等级门槛 ----------
            JSONObject lv = root.getJSONObject("levels");
            lvWatch = lv.optInt("watch", 25);
            lvWarn = lv.optInt("warn", 45);
            lvHigh = lv.optInt("high", 70);
            lvBlock = lv.optInt("block", 90);

            // ---------- 第六步：读组合判据 ----------
            JSONArray cs = root.optJSONArray("combos");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject o = cs.getJSONObject(i);
                    Combo c = new Combo();
                    c.level = o.optString("level", "watch");
                    c.why = o.optString("why", "");
                    JSONArray all = o.optJSONArray("all");
                    if (all == null) continue;
                    for (int j = 0; j < all.length(); j++) {
                        Cond cond = parseCond(all.getString(j));
                        if (cond != null) c.conds.add(cond);
                    }
                    if (c.conds.size() > 0) combos.add(c);
                }
            }

            // ---------- 第七步：读官方宣传免打扰配置 ----------
            // 反诈宣传短信本身就是一堆高危词（共享屏幕/安全账户/验证码），
            // 不豁免的话运营商每周推一条，老人会被反复盖屏到卸载 App。
            JSONObject nt = root.optJSONObject("notice_alert");
            if (nt != null) {
                noticeOn = nt.optBoolean("enabled", false);
                noticeWho = nt.optString("onlyWho", "service");
                noticeMin = nt.optInt("minMarkers", 2);
                JSONArray mk = nt.optJSONArray("markers");
                if (mk != null) for (int i = 0; i < mk.length(); i++) noticeMarkers.add(mk.getString(i));
                JSONArray vt = nt.optJSONArray("veto");
                if (vt != null) for (int i = 0; i < vt.length(); i++) noticeVeto.add(vt.getString(i));
            }

            loaded = true;

        } catch (Exception e) {
            loaded = false;
            loadError = e.getMessage();
        }
    }

    // ==========================================================
    //  五、禁止字段检查（这一段是"守门员"）
    // ==========================================================
    private void checkForbidden(JSONObject root) throws Exception {
        // 注释字段（下划线开头的）里可能提到"call.content"这类词做说明，
        // 那是给人看的，不该报错。所以先把注释剔掉再查。
        JSONObject clean = new JSONObject();
        Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!k.startsWith("_")) clean.put(k, root.get(k));
        }

        String serious = clean.toString().toLowerCase();
        for (String f : FORBIDDEN) {
            if (serious.contains(f)) {
                throw new Exception(
                        "规则里出现了拿不到的字段：" + f +
                        "。安卓不允许第三方 App 读通话内容，请改用可观测的动作信号。");
            }
        }
    }

    // ==========================================================
    //  六、短信判定
    // ==========================================================
    /**
     * 判一条短信。
     *
     * @param body  正文
     * @param who   号码身份 contact / service / stranger
     * @param isCode 是不是验证码短信
     * @return 判定结果
     */
    public Result judgeSms(String body, String who, boolean isCode) {
        Result r = new Result();
        if (!loaded) {
            r.stage = "S0";
            r.level = "none";
            r.why = "规则未加载：" + loadError;
            return r;
        }
        if (body == null) body = "";
        String text = body.toLowerCase();

        // ---------- 1. 逐词打分 ----------
        int score = 0;
        StringBuilder hits = new StringBuilder();
        // 2026-11-04 新增：一边打分一边记下"命中的最高阶段词"。
        // 阶段判定已从"总分落桶"改为"最高命中阶段词"——分数衡量可疑度，
        // 阶段问的是"骗子走到第几步"，两件事必须分开算。
        String topStage = "S0";
        int topRank = 0;
        for (Word w : words) {
            // 已经是"验证码短信"时，「验证码」这个词必然出现，第 4 步会按发送方单独处理，
            // 词库不再重复计分 —— 否则银行正常验证码短信会被 "验证码+S4" 直接推到盖屏。
            if (isCode && "验证码".equals(w.text)) continue;
            if (text.contains(w.text.toLowerCase())) {
                score += w.weight;
                if (hits.length() > 0) hits.append("、");
                hits.append(w.text).append("(+").append(w.weight).append(")");
                if (w.stage != null && w.weight > 0) {
                    int rk = stageRankOf(w.stage);
                    if (rk > topRank) {
                        topRank = rk;
                        topStage = w.stage;
                    }
                }
            }
        }

        // ---------- 2. 反例扣分 ----------
        for (Word w : negatives) {
            if (text.contains(w.text.toLowerCase())) {
                score += w.weight;      // weight 是负数
                if (hits.length() > 0) hits.append("、");
                hits.append(w.text).append("(").append(w.weight).append(")");
            }
        }

        // ---------- 3. 号码身份加权 ----------
        // 同样的内容，陌生人发的比服务号发的危险。
        // 这一步不写在 rules.json 里，因为它不是"词"，是"身份"。
        if ("stranger".equals(who)) {
            score += 15;
            if (hits.length() > 0) hits.append("、");
            hits.append("陌生号码(+15)");
        } else if ("contact".equals(who) && score > 0) {
            // 通讯录里的号码 = 已知熟人，给 −20 基础信任分。
            // 「妈，把身份证号发给我，我帮你订票」这类家常请求和套信息句式高度相似，
            // 光靠词库分不开，只能用身份分兜底。只在本来就有可疑分时才打折，避免负数脏数据。
            score -= 20;
            if (hits.length() > 0) hits.append("、");
            hits.append("通讯录号码(-20)");
        }
        // ---------- 3.5 链接特征 ----------
        // 【为什么单独立一条，不写进词库】
        //   钓鱼短信的核心动作是"让你点一个链接"。域名每天换、短链天天新，
        //   词库永远穷举不完。所以这里认的是"形式"而不是"内容"：
        //   只要正文里出现一个可点的网址，不管它长得像不像官方，都算钓鱼入口。
        //
        // 【为什么不联网核验"这域名是不是真的工商银行"】
        //   短信到达的那一秒来不及查，而且离线也必须能判。
        //   我们只回答一个问题：这条短信在催你点链接吗？是，就加分。
        //
        // 【2026-11-09 加】实测：'您的ETC已停用，请点击 t.cn/Etc9 认证'
        //   词库只给 55 分，因为骗子故意避开'点击链接'四个字。补上形式特征才抓得住。
        Matcher um = URL_PATTERN.matcher(body);
        if (um.find()) {
            score += 20;
            if (hits.length() > 0) hits.append("、");
            hits.append("含链接(+20)");
            if (stageRankOf("S2") > topRank) {
                topRank = 2;
                topStage = "S2";
            }
        }

        // 验证码短信要按"谁发的"区别对待：
        //   服务号（95588、银行、平台）发验证码 = 正常业务，不加分
        //   陌生号码发验证码 = 极不正常，重罚
        //
        // 为什么这么判：验证码的正常来源只有服务号。陌生人给你发"验证码"，
        // 只可能是钓鱼（让你点链接、或者冒充客服要你念出来）。
        if (isCode) {
            if ("service".equals(who)) {
                if (hits.length() > 0) hits.append("、");
                hits.append("服务号验证码(+0)");
            } else {
                // v3.4：+30 → +45，与云端 Judge.js 一致
                score += 45;
                if (hits.length() > 0) hits.append("、");
                hits.append("非服务号发验证码(+45)");
                // 与云端 Judge.js 对齐：这个信号在 JS 里是 tag:'S4'，这边也要参与定阶段
                if (stageRankOf("S4") > topRank) {
                    topRank = 4;
                    topStage = "S4";
                }
            }
        }

        // ---------- 4.5 官方宣传短信免打扰 ----------
        // 与云端 Judge.js 的 noticeOf() 逐条对齐：来源 service + 命中≥2个宣传标记 + 无任何 veto 词。
        String noticeWhy = noticeOf(text, who);
        if (noticeWhy != null) {
            r.score = score;
            r.stage = "S0";
            r.level = "none";
            r.exempt = "publicity";
            r.why = noticeWhy + "，判为官方宣传短信，不打扰";
            r.hint = hintOf("S0");
            return r;
        }

        // 【2026-11-04】原来这里把负分夹到 0。已去掉：云端 Judge.js 从不夹，
        // 两端分数不一致会让对拍工具（tools/modelcheck）永远对不上。
        // 负分只表示"比基准更不像诈骗"，阶段/等级都已经是 S0/none，不影响任何行为。

        // ---------- 4. 定阶段、定级别 ----------
        r.score = score;
        r.stage = stageOf(score, topStage, topRank);
        r.level = levelOf(score, r.stage);
        // 通讯录里的号码最多"提醒"，不盖屏 —— 除非已经明确走到要钱(S4)。
        if ("contact".equals(who) && !"S4".equals(r.stage) && levelRank(r.level) > levelRank("watch")) {
            r.level = "watch";
        }
        r.why = hits.length() == 0 ? "没命中任何可疑词" : hits.toString();
        r.hint = hintOf(r.stage);
        return r;
    }

    // ==========================================================
    //  七、状态判定（组合判据在这里生效）
    // ==========================================================
    /**
     * 拿"当前手机状态"过一遍组合判据。
     *
     * 【为什么组合判据要单独一套】
     * 因为单条信号都能解释：陌生号码能解释（外卖）、装了 App 能解释（下个工具）、
     * 打开银行能解释（查余额）。三条单独看都不算事。
     *
     * 但"正在和一个陌生人通话 45 分钟" + "同时打开了手机银行"—— 这个组合
     * 在生活里几乎不存在。这就是它的价值。
     *
     * @param st 当前状态快照
     * @return 命中的最高级别结果；一条都没命中就返回 none
     */
    public Result judgeState(State st) {
        Result best = new Result();
        best.stage = "S0";
        best.level = "none";
        best.score = 0;
        best.why = "";
        if (!loaded || st == null) return best;

        int bestRank = 0;
        for (Combo c : combos) {
            boolean all = true;
            for (Cond cond : c.conds) {
                if (!match(cond, st)) { all = false; break; }
            }
            if (!all) continue;

            int rank = rankOf(c.level);
            if (rank > bestRank) {
                bestRank = rank;
                best.level = c.level;
                best.why = c.why;
                best.score = rank * 20;
            }
        }
        if (bestRank > 0) {
            best.stage = stageOf(best.score);
            best.hint = hintOf(best.stage);
        }
        return best;
    }

    /** 当前手机状态快照。谁采集的谁填，判定只管读。 */
    public static class State {
        public String callWho = null;        // stranger / contact / null(没在通话)
        public int callDur = 0;              // 通话已进行秒数
        public int callDirection = 0;        // 1=呼入 2=呼出
        public int rejectFamily = 0;         // 这次通话中拒接家人几次
        public boolean bankForeground = false;   // 前台是不是银行/支付类
        public boolean unknownInstall = false;   // 装过非商店 APK
        public boolean a11yGranted = false;      // 装的那玩意开了无障碍
        public boolean codeInCall = false;       // 本次通话里收到过验证码

        public boolean inCall() { return callWho != null; }
    }

    // ==========================================================
    //  八、规则条件的解析与匹配
    // ==========================================================

    /**
     * 把 "call.dur>300" 拆成 字段=call.dur、运算符=>、值=300
     *
     * 支持：= > < >= <=
     * 注意先判两字符的运算符，再判一字符的，否则 ">=" 会被当成 ">"
     */
    private Cond parseCond(String s) {
        if (s == null) return null;
        String[] ops = {">=", "<=", ">", "<", "="};
        for (String op : ops) {
            int i = s.indexOf(op);
            if (i > 0) {
                Cond c = new Cond();
                c.field = s.substring(0, i).trim();
                c.op = op;
                c.value = s.substring(i + op.length()).trim();
                return c;
            }
        }
        return null;
    }

    /** 一条条件在当前状态下成不成立 */
    private boolean match(Cond c, State st) {
        String v = stateValue(c.field, st);
        if (v == null) return false;

        // 数字比较
        if (c.op.equals(">") || c.op.equals("<") || c.op.equals(">=") || c.op.equals("<=")) {
            try {
                double a = Double.parseDouble(v);
                double b = Double.parseDouble(c.value);
                if (c.op.equals(">")) return a > b;
                if (c.op.equals("<")) return a < b;
                if (c.op.equals(">=")) return a >= b;
                return a <= b;
            } catch (Exception e) {
                return false;
            }
        }
        // 等值比较（忽略大小写）
        return v.equalsIgnoreCase(c.value);
    }

    /**
     * 把规则里的字段名，翻译成当前状态里的实际值。
     *
     * 【这里为什么要翻译一道】
     * 因为规则是人写的，写的是 "call.who=stranger" 这种人话。
     * 代码里没有 call.who 这个变量，只有 State.callWho。
     * 这一层翻译就是把"人话"转成"变量"。规则加新字段，就在这里加一行。
     */
    private String stateValue(String field, State st) {
        if ("call.who".equals(field))        return st.inCall() ? st.callWho : null;
        if ("call.dur".equals(field))        return String.valueOf(st.callDur);
        if ("call.rejectFamily".equals(field)) return String.valueOf(st.rejectFamily);
        if ("call.direction".equals(field))  return st.callDirection == 1 ? "in" : (st.callDirection == 2 ? "out" : null);
        // app.pkg=bank 里的 "bank" 不是真包名，是"能转钱的一类 App"的代称
        if ("app.pkg".equals(field))         return st.bankForeground ? "bank" : "other";
        // 【2026-11-05 补】规则里最硬的那条组合写的是 app.isBank=true，
        // 这里原来没有这一行 —— 翻译不出来就返回 null，条件永远不成立，
        // 等于"通话中打开手机银行"这条判据在手机上从来没生效过。
        // 模拟器上用假银行 App 复现时才暴露（之前一直靠"通话中收验证码"顶着，所以没发现）。
        if ("app.isBank".equals(field))      return st.bankForeground ? "true" : "false";
        if ("install.fromStore".equals(field)) return st.unknownInstall ? "false" : "true";
        if ("a11y.granted".equals(field))    return st.a11yGranted ? "true" : "false";
        if ("sms.isCode".equals(field))      return st.codeInCall ? "true" : "false";
        return null;   // 不认识的字段：条件永远不成立（而不是永远成立）
    }

    // ==========================================================
    //  九、换算：分数 → 阶段 / 级别
    // ==========================================================
    /** 旧版：纯按分数落桶。只在拿不到命中明细时兜底用（判断情景组合的阶段）。 */
    private String stageOf(int score) {
        for (int i = 4; i >= 0; i--) {
            if (score >= stageNeed[i]) return "S" + i;
        }
        return "S0";
    }

    /**
     * 阶段判定：分数 + 命中的最高阶段词 → 骗子走到第几步
     *
     * 【2026-11-04 模型修正，与云端 Judge.js 逐行对齐】
     * 原实现是"总分落桶"：≥90 判 S4、≥70 判 S3。被 A 线 30 条测试集证伪，判对率仅 57%；
     * 7 组阈值敏感性实验全部落在 53%~57%，证明问题在模型而不在阈值：
     *   一条堆满「社保局+涉嫌+冻结」的短信能凑 93 分被判成"要钱"，可它一分钱没要；
     *   反过来「把验证码报给我」这种标准要钱短信只有 55 分，被判成"吓唬"。
     *
     * 新模型：阶段由"命中了哪一阶段的词"说话，分数只当最低门槛。
     * 手机端必须和云端算出同一个结果 —— 否则子女端显示"你妈走到第4步"，
     * 老人手机上却写着"第2步"，演示现场就要出事。
     */
    private String stageOf(int score, String topStage, int topRank) {
        if (topStage != null && !"S0".equals(topStage)) {
            int idx = topStage.length() > 1 ? topStage.charAt(1) - '0' : 0;
            if (idx >= 0 && idx <= 4 && score >= stageNeed[idx]) return topStage;
        }
        // 命中了 S2 以上的话术但分数没强到能定级 → 至少认定为"已进入话术流程"，
        // 退回 S1，不要当成无风险放过去。
        // （topRank 在这里不改变结果，保留它是为了让两边代码结构对得上）
        return score >= stageNeed[1] ? "S1" : "S0";
    }

    /**
     * 官方宣传短信识别 —— 免打扰的唯一出口。与云端 Judge.js 的 noticeOf() 逐条对齐。
     *
     * 【2026-11-04 新增】反诈宣传短信本身就是一堆高危词：
     *   「【中国移动】警示：近期冒充客服要求共享屏幕的诈骗高发，请勿配合。」
     * 旧逻辑判 S4/block，运营商每周推一条，老人会被反复盖屏到直接卸载 App。
     *
     * 判别依据不是词，是句式人称：宣传是第三人称告诫（"警惕冒充…""诈骗高发"），
     * 诈骗是第二人称指令（"请将资金转入…""把验证码报给我"）。
     *
     * 三道闸门全过才免打扰：来源必须 service、命中≥minMarkers 个宣传标记、不含任何 veto 词。
     */
    private String noticeOf(String text, String who) {
        if (!noticeOn) return null;
        if (noticeWho != null && noticeWho.length() > 0 && !noticeWho.equals(who)) return null;

        StringBuilder found = new StringBuilder();
        int n = 0;
        for (String m : noticeMarkers) {
            if (text.contains(m.toLowerCase())) {
                n++;
                if (found.length() > 0) found.append("、");
                found.append(m);
            }
        }
        if (n < noticeMin) return null;

        for (String v : noticeVeto) {
            if (text.contains(v.toLowerCase())) return null;  // 出现第二人称指令 → 取消防豁免
        }
        return "官方宣传(" + found + ")";
    }

    /** 等级高低序：用来判断"有没有超过某个等级" */
    private int levelRank(String level) {
        if ("block".equals(level)) return 4;
        if ("high".equals(level))  return 3;
        if ("warn".equals(level))  return 2;
        if ("watch".equals(level)) return 1;
        return 0;
    }

    /** 阶段高低序：用来在一堆命中词里挑出"骗子走到的最远那一步" */
    private int stageRankOf(String stage) {
        if (stage == null || stage.length() < 2) return 0;
        char c = stage.charAt(1);
        if (c == '4') return 4;
        if (c == '3') return 3;
        if (c == '2') return 2;
        if (c == '1') return 1;
        return 0;
    }

    /** 旧版：纯按分数落桶 */
    private String levelOf(int score) {
        if (score >= lvBlock) return "block";
        if (score >= lvHigh)  return "high";
        if (score >= lvWarn)  return "warn";
        if (score >= lvWatch) return "watch";
        return "none";
    }

    /**
     * 风险等级：决定盖不盖屏、推不推送、打不打电话
     *
     * 【2026-11-04 修正】原来纯按分数，结果和阶段倒挂：
     *   A 线 30 条里 5 条"要钱"短信没有一条盖屏，反倒 2 条"只是吓唬"的盖了屏。
     * 新规则跟阶段走，分数只在 S3 上做一次加严判断（隔离是本产品的核心观测点）。
     */
    private String levelOf(int score, String stage) {
        if (stage == null) return levelOf(score);
        if ("S4".equals(stage)) return "block";                      // 要钱：一律盖屏
        if ("S3".equals(stage)) return score >= lvHigh ? "block" : "high";
        // v3.4：同一阶段信号叠得多也要升级（与云端 Judge.js 一致）
        if ("S2".equals(stage)) return score >= lvHigh ? "high" : "warn";
        if ("S1".equals(stage)) return score >= lvWarn ? "warn" : "watch";
        return "none";
    }

    private int rankOf(String level) {
        if ("block".equals(level)) return 4;
        if ("high".equals(level))  return 3;
        if ("warn".equals(level))  return 2;
        if ("watch".equals(level)) return 1;
        return 0;
    }

    /** 给老人看的一句话。不说"诈骗"，只说事实。 */
    private String hintOf(String stage) {
        if ("S4".equals(stage)) return "对方在要你的钱";
        if ("S3".equals(stage)) return "对方让你别告诉家人";
        if ("S2".equals(stage)) return "对方在吓唬你";
        if ("S1".equals(stage)) return "对方在套近乎";
        return "";
    }

    // ==========================================================
    //  十、公用工具
    // ==========================================================
    /** 判断一个包名/应用名是不是"能转钱的" */
    public static boolean isBank(String pkg, String label) {
        String s = ((pkg == null ? "" : pkg) + " " + (label == null ? "" : label)).toLowerCase();
        for (String w : BANK_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ==========================================================
    //  十一、判定结果
    // ==========================================================
    public static class Result {
        public String stage = "S0";   // S0..S4
        public String level = "none"; // none / watch / warn / high / block
        public int score = 0;
        public String why = "";       // 命中了什么（内部用 + 子女端展示）
        public String hint = "";      // 给老人看的一句话
        public String exempt = null;  // 非空表示被豁免，值为 "publicity"（官方宣传短信）

        /** 拼成推给网页的 JSON 片段 */
        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("stage", stage);
                o.put("level", level);
                o.put("score", score);
                o.put("why", why);
                o.put("hint", hint);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}
