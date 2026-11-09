package com.yindun.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ============================================================================
 *  DrillActivity —— 反诈演练（原生界面）
 * ----------------------------------------------------------------------------
 *  为什么不塞进 WebView：
 *    老人要看清字，原生能直接控字号、行高、按钮高度，WebView 里调这些会被
 *    各种默认样式干扰；而且演练要"点了立刻有反应"，少一层 WebView 少一层卡。
 *
 *  三种状态：
 *    HOME   选场景，看上次成绩
 *    PLAY   骗子说一句，老人选一个应对（4 轮）
 *    RESULT 成绩单，指出错在第几步
 *
 *  断网策略：任何一次请求失败，立刻切到内置剧本（SC 常量）。
 *    现场演示最怕"转圈"，宁可演简化版，也不能白屏。
 * ============================================================================
 */
public class DrillActivity extends Activity {

    // ---------- 配色（和主界面一套） ----------
    private static final int C_BG      = Color.parseColor("#070b16");
    private static final int C_PANEL   = Color.parseColor("#0f1524");
    private static final int C_PANEL2  = Color.parseColor("#141c30");
    private static final int C_LINE    = Color.parseColor("#1e293b");
    private static final int C_TXT     = Color.parseColor("#e5e7eb");
    private static final int C_TXT2    = Color.parseColor("#94a3b8");
    private static final int C_TXT3    = Color.parseColor("#64748b");
    private static final int C_ACCENT  = Color.parseColor("#22d3ee");
    private static final int C_OK      = Color.parseColor("#22c55e");
    private static final int C_BAD     = Color.parseColor("#ef4444");
    private static final int C_WARN    = Color.parseColor("#f59e0b");

    // ---------- 界面骨架 ----------
    private LinearLayout body;      // 内容容器
    private TextView titleSub;      // 顶部小字（第几轮 / 状态）

    // ---------- 演练状态 ----------
    private boolean online = false;         // 这一局是否走的云端
    private String drillId = null;
    private String scenarioKey = "";
    private String scenarioName = "";
    private String scenarioIcon = "🎯";
    /** 这一局从哪来的（热点 / 你上传的案例 / 跨套路叠加），由云端返回 */
    private String fromText = "";
    /** 云端已存了几条上传的诈骗信息，决定"按我上传的演"能不能点 */
    private volatile int caseCount = 0;
    private int idx = 0;
    private int total = 4;
    private int correctCount = 0;
    private int[] marks = new int[8];       // 0未答 1对 2错
    private Scenario localSc = null;        // 断网时用的剧本
    private Round curRound = null;
    private final java.util.List<int[]> wrongLog = new java.util.ArrayList<int[]>(); // 用不到，占位避免误用

    // ============================================================================
    //  生命周期
    // ============================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildShell();
        showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DrillAlarm.schedule(this);   // 每次打开都确保下次提醒还活着（国产 ROM 常清闹钟）
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ---------- 顶栏 ----------
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(14), dp(10), dp(12));
        top.setBackgroundColor(C_PANEL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("🎯 反诈演练");
        t1.setTextColor(C_TXT);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleSub = new TextView(this);
        titleSub.setText("AI 扮骗子，4 轮攻防");
        titleSub.setTextColor(C_TXT3);
        titleSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        titles.addView(t1);
        titles.addView(titleSub);
        LinearLayout.LayoutParams lpTitle = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(titles, lpTitle);

        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextColor(C_TXT2);
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        gear.setPadding(dp(12), dp(6), dp(4), dp(6));
        gear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showServerDialog(); }
        });
        top.addView(gear);

        TextView back = new TextView(this);
        back.setText("返回");
        back.setTextColor(C_ACCENT);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        back.setPadding(dp(10), dp(8), dp(8), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        top.addView(back);

        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 内容区（可滚动） ----------
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(28));
        sv.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    // ============================================================================
    //  状态一：首页
    // ============================================================================

    private void showHome() {
        titleSub.setText("AI 扮骗子，4 轮攻防");
        body.removeAllViews();

        // 计划条
        final LinearLayout planCard = card();
        planCard.setBackground(roundRect(0xFF0d1b2a, C_ACCENT, 1));
        TextView pl = label("下次演练", 11, C_ACCENT, true);
        planCard.addView(pl);
        final TextView pv = label("读取中…", 17, C_TXT, true);
        pv.setPadding(0, dp(6), 0, 0);
        planCard.addView(pv);
        final TextView ph = label("", 11, C_TXT3, false);
        ph.setPadding(0, dp(4), 0, 0);
        planCard.addView(ph);
        body.addView(planCard);

        // ---------- 怎么出题：热点 / 上传案例 / 跨套路混合 ----------
        body.addView(sectionTitle("怎么出题"));
        final String[][] MODES = {
            {"🔥", "按热点演", "用最近高发的诈骗类型现编一局", "hot"},
            {"📮", "按我上传的演", "读取中…", "case"},
            {"🎭", "跨套路混合", "两层壳：先建信任，再收割", "mix"},
        };
        TextView caseDesc = null;
        for (int i = 0; i < MODES.length; i++) {
            final String mode = MODES[i][3];
            LinearLayout c = card();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView ico = label(MODES[i][0], 22, C_TXT, false);
            ico.setPadding(0, 0, dp(12), 0);
            row.addView(ico);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(label(MODES[i][1], 15, C_TXT, true));
            TextView d = label(MODES[i][2], 11, C_TXT3, false);
            col.addView(d);
            if ("case".equals(mode)) caseDesc = d;
            row.addView(col);
            c.addView(row);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 没上传过就只能先按热点演，否则后台会悄悄退回热点，用户看不懂
                    if ("case".equals(mode) && caseCount == 0) {
                        Toast.makeText(DrillActivity.this, "还没上传过诈骗信息，先按热点演一局", Toast.LENGTH_SHORT).show();
                        startDrill("", "hot");
                        return;
                    }
                    startDrill("", mode);
                }
            });
            body.addView(c);
        }
        final TextView caseDescRef = caseDesc;

        // 场景卡片
        body.addView(sectionTitle("经典剧本（固定四套，断网也能练）"));
        for (int i = 0; i < SC.length; i++) {
            final Scenario s = SC[i];
            LinearLayout c = card();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView ico = label(s.icon, 26, C_TXT, false);
            ico.setPadding(0, 0, dp(12), 0);
            row.addView(ico);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(label(s.name, 15, C_TXT, true));
            col.addView(label(s.desc, 11, C_TXT3, false));
            row.addView(col);
            c.addView(row);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { startDrill(s.key); }
            });
            body.addView(c);
        }

        // 历史
        body.addView(sectionTitle("练习记录"));
        final LinearLayout hist = card();
        hist.addView(label("读取中…", 12, C_TXT3, false));
        body.addView(hist);

        // 异步拉计划 + 记录
        new Thread(new Runnable() {
            public void run() {
                final JSONObject plan = DrillNet.get(DrillActivity.this, "/api/drill/plan");
                final JSONObject cs = DrillNet.get(DrillActivity.this, "/api/drill/cases");
                ui(new Runnable() {
                    public void run() {
                        // 先更新"按我上传的演"那张卡：有没有素材，用户得看得见
                        if (caseDescRef != null) {
                            JSONArray L = (cs == null) ? null : cs.optJSONArray("list");
                            caseCount = (L == null) ? 0 : L.length();
                            if (caseCount > 0) {
                                String tp = L.optJSONObject(0).optString("type", "案例");
                                caseDescRef.setText("拿你传的「" + tp + "」做种子，换外壳重演");
                            } else {
                                caseDescRef.setText("还没上传过诈骗信息，先传一条再来");
                            }
                        }
                        if (plan == null) {
                            pv.setText(DrillNet.hasBase(DrillActivity.this) ? "连不上云端，先能练" : "还没配服务器地址");
                            ph.setText("点右上角 ⚙ 填一下服务器地址；不填就用内置剧本练");
                            hist.removeAllViews();
                            hist.addView(label("还没练过。第一局不怕答错，练的就是答错之后记住。", 12, C_TXT3, false));
                            return;
                        }
                        try {
                            JSONObject p = plan.getJSONObject("plan");
                            pv.setText(nextText(p));
                            JSONObject st = plan.optJSONObject("stats");
                            String extra = "当前频率：" + freqText(p.optString("freq"));
                            if (st != null && st.optInt("count") > 0) {
                                extra += " · 已练 " + st.optInt("count") + " 局，平均 " + st.optInt("avg") + " 分";
                            }
                            ph.setText(extra);

                            hist.removeAllViews();
                            if (st != null && st.optJSONArray("trend") != null && st.optJSONArray("trend").length() > 0) {
                                JSONArray tr = st.optJSONArray("trend");
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < tr.length(); i++) {
                                    if (i > 0) sb.append(" → ");
                                    sb.append(tr.getJSONObject(i).optInt("score"));
                                }
                                hist.addView(label("最近成绩：" + sb.toString(), 13, C_TXT, false));
                                JSONArray weak = st.optJSONArray("weak");
                                if (weak != null && weak.length() > 0) {
                                    StringBuilder w = new StringBuilder("最常失守：");
                                    for (int i = 0; i < weak.length(); i++) {
                                        if (i > 0) w.append("、");
                                        w.append(weak.getJSONObject(i).optString("name"))
                                         .append("（").append(weak.getJSONObject(i).optInt("times")).append("次）");
                                    }
                                    TextView wt = label(w.toString(), 12, C_WARN, false);
                                    wt.setPadding(0, dp(8), 0, 0);
                                    hist.addView(wt);
                                }
                            } else {
                                hist.addView(label("还没练过。第一局不怕答错，练的就是答错之后记住。", 12, C_TXT3, false));
                            }
                        } catch (Exception e) {
                            pv.setText("数据格式不对");
                        }
                    }
                });
            }
        }).start();
    }

    // ============================================================================
    //  状态二：演练中
    // ============================================================================

    private void startDrill(final String key) {
        startDrill(key, "");
    }

    /**
     * 开局。
     *   key  经典剧本的 key（gongan/qinshu/kefu/shuadan），为空表示不用经典剧本
     *   mode hot / case / mix / auto —— 让云端按热点、按上传案例或跨套路现编一局
     */
    private void startDrill(final String key, final String mode) {
        body.removeAllViews();
        titleSub.setText("AI 正在扮演骗子…");
        body.addView(label("正在现编台词，大约 2 秒…", 13, C_TXT3, false));

        new Thread(new Runnable() {
            public void run() {
                JSONObject req = new JSONObject();
                try {
                    if (key != null && key.length() > 0) req.put("scenario", key);
                    if (mode != null && mode.length() > 0) req.put("mode", mode);
                } catch (Exception ignore) { }
                final JSONObject r = DrillNet.post(DrillActivity.this, "/api/drill/start", req);

                ui(new Runnable() {
                    public void run() {
                        online = (r != null && r.optBoolean("ok"));
                        if (online) {
                            drillId = r.optString("drillId");
                            total = r.optInt("total", 4);
                            idx = r.optInt("idx", 0);
                            correctCount = 0;
                            marks = new int[8];
                            scenarioKey = key;
                            fromText = r.optString("from", "");
                            try {
                                JSONObject sc = r.optJSONObject("scenario");
                                scenarioName = sc.optString("name", "");
                                scenarioIcon = sc.optString("icon", "🎯");
                            } catch (Exception e) { scenarioName = ""; }
                            curRound = parseRound(r.optJSONObject("round"));
                            renderRound();
                        } else {
                            // ---------- 断网兜底：走内置剧本 ----------
                            localSc = findScenario(key);
                            if (localSc == null) localSc = SC[0];
                            online = false;
                            drillId = null;
                            total = localSc.rounds.length;
                            idx = 0;
                            correctCount = 0;
                            marks = new int[8];
                            scenarioKey = localSc.key;
                            scenarioName = localSc.name;
                            scenarioIcon = localSc.icon;
                            fromText = "内置剧本（云端没连上）";
                            curRound = localSc.rounds[0];
                            Toast.makeText(DrillActivity.this, "连不上云端，使用内置剧本", Toast.LENGTH_SHORT).show();
                            renderRound();
                        }
                    }
                });
            }
        }).start();
    }

    private void renderRound() {
        body.removeAllViews();
        titleSub.setText(scenarioIcon + " " + scenarioName + " · 第 " + (idx + 1) + "/" + total + " 轮");

        // 进度条
        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < total; i++) {
            View v = new View(this);
            int color = C_LINE;
            if (marks[i] == 1) color = C_OK;
            else if (marks[i] == 2) color = C_BAD;
            else if (i == idx) color = C_ACCENT;
            v.setBackground(roundRect(color, color, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1f);
            lp.rightMargin = dp(5);
            prog.addView(v, lp);
        }
        LinearLayout progWrap = new LinearLayout(this);
        progWrap.setOrientation(LinearLayout.VERTICAL);
        progWrap.setPadding(0, 0, 0, dp(16));
        progWrap.addView(prog, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        body.addView(progWrap);

        // 本局来源：热点类型 / 你上传的案例 / 两者叠加。让老人知道这一局是怎么来的
        if (fromText != null && fromText.length() > 0) {
            LinearLayout fr = new LinearLayout(this);
            fr.setBackground(roundRect(0xFF0f1a2b, C_ACCENT, 0));
            fr.setPadding(dp(11), dp(8), dp(11), dp(8));
            fr.addView(label("本局来自：" + fromText, 11, C_TXT2, false));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.bottomMargin = dp(14);
            body.addView(fr, flp);
        }

        // 骗子气泡
        LinearLayout bubbleCard = card();
        bubbleCard.setBackground(roundRect(C_PANEL2, C_LINE, 1));
        TextView who = label("对方 · 第 " + (idx + 1) + " 轮", 10, Color.parseColor("#fca5a5"), true);
        bubbleCard.addView(who);
        TextView ai = label(curRound.ai, 17, C_TXT, false);
        ai.setLineSpacing(dp(4), 1f);
        ai.setPadding(0, dp(8), 0, 0);
        bubbleCard.addView(ai);
        body.addView(bubbleCard);

        // 问题 + 选项
        TextView q = label("你会怎么做？", 13, C_TXT2, false);
        q.setPadding(dp(2), dp(16), 0, dp(10));
        body.addView(q);

        for (int i = 0; i < curRound.opts.length; i++) {
            final int pick = i;
            Button btn = new Button(this);
            btn.setText(curRound.opts[i]);
            btn.setAllCaps(false);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            btn.setTextColor(C_TXT);
            btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            btn.setPadding(dp(16), dp(14), dp(16), dp(14));
            btn.setBackground(roundRect(C_PANEL2, C_LINE, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pickOption(pick); }
            });
            body.addView(btn, lp);
        }

        LinearLayout tipBox = new LinearLayout(this);
        tipBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(tipBox);
    }

    private void pickOption(final int pick) {
        // 锁住按钮，避免连点
        for (int i = 0; i < body.getChildCount(); i++) {
            View v = body.getChildAt(i);
            if (v instanceof Button) v.setEnabled(false);
        }
        body.addView(label("判分中…", 12, C_TXT3, false));

        new Thread(new Runnable() {
            public void run() {
                boolean correct;
                String tip;
                String rightText;
                JSONObject nextObj = null;
                JSONObject result = null;

                if (online) {
                    JSONObject req = new JSONObject();
                    try {
                        req.put("drillId", drillId);
                        req.put("choice", pick);
                    } catch (Exception ignore) { }
                    JSONObject r = DrillNet.post(DrillActivity.this, "/api/drill/turn", req);
                    if (r != null && r.optBoolean("ok")) {
                        correct = r.optBoolean("correct");
                        tip = r.optString("tip");
                        rightText = r.optString("right");
                        nextObj = r.optJSONObject("next");
                        result = r.optJSONObject("result");
                    } else {
                        // 网络中途断了：就地按本地剧本判
                        Round lr = localSc != null ? localSc.rounds[Math.min(idx, localSc.rounds.length - 1)] : curRound;
                        correct = (pick == lr.ok);
                        tip = lr.tip;
                        rightText = lr.opts[lr.ok];
                        result = null;
                        nextObj = null;
                    }
                } else {
                    correct = (pick == curRound.ok);
                    tip = curRound.tip;
                    rightText = curRound.opts[curRound.ok];
                }

                final boolean fc = correct;
                final String ftip = tip;
                final String fright = rightText;
                final JSONObject fnext = nextObj;
                final JSONObject fresult = result;

                ui(new Runnable() {
                    public void run() {
                        if (fc) { correctCount++; marks[idx] = 1; } else { marks[idx] = 2; }
                        markAndShow(fc, fright, ftip);

                        if (fresult != null) {
                            // 云端结算完毕
                            final JSONObject rr = fresult;
                            postDelayed(new Runnable() {
                                public void run() { renderResultCloud(rr); }
                            }, 1600);
                            return;
                        }
                        if (fnext != null && fnext.optJSONObject("round") != null) {
                            final JSONObject nr = fnext;
                            postDelayed(new Runnable() {
                                public void run() {
                                    idx = nr.optInt("idx", idx + 1);
                                    total = nr.optInt("total", total);
                                    curRound = parseRound(nr.optJSONObject("round"));
                                    renderRound();
                                }
                            }, 1600);
                            return;
                        }
                        // 本地模式：自己推进
                        final int nidx = idx + 1;
                        postDelayed(new Runnable() {
                            public void run() {
                                if (localSc != null && nidx < localSc.rounds.length) {
                                    idx = nidx;
                                    curRound = localSc.rounds[nidx];
                                    renderRound();
                                } else {
                                    renderResultLocal();
                                }
                            }
                        }, 1600);
                    }
                });
            }
        }).start();
    }

    /** 标出对错 + 显示解释 */
    private void markAndShow(boolean correct, String rightText, String tip) {
        // 去掉"判分中"
        int last = body.getChildCount() - 1;
        if (last >= 0) body.removeViewAt(last);

        for (int i = 0; i < body.getChildCount(); i++) {
            View v = body.getChildAt(i);
            if (v instanceof Button) {
                Button btn = (Button) v;
                String t = btn.getText().toString();
                if (t.equals(rightText)) {
                    btn.setBackground(roundRect(0x2222c55e, C_OK, 1));
                    btn.setTextColor(Color.parseColor("#86efac"));
                } else {
                    btn.setBackground(roundRect(C_PANEL2, C_LINE, 1));
                    btn.setTextColor(C_TXT3);
                }
            }
        }

        LinearLayout fb = card();
        fb.setBackground(roundRect(correct ? 0x2222c55e : 0x33ef4444, correct ? C_OK : C_BAD, 1));
        TextView h = label(correct ? "✔ 这一步做对了" : "✘ 这一步没守住", 14, correct ? Color.parseColor("#86efac") : Color.parseColor("#fca5a5"), true);
        fb.addView(h);
        TextView bd = label(tip, 13, C_TXT, false);
        bd.setLineSpacing(dp(3), 1f);
        bd.setPadding(0, dp(8), 0, 0);
        fb.addView(bd);
        body.addView(fb);
    }

    // ============================================================================
    //  状态三：成绩单
    // ============================================================================

    private void renderResultCloud(JSONObject res) {
        final int score = res.optInt("score", 0);
        final boolean passed = res.optBoolean("passed", score >= 75);
        String comment = res.optString("comment", "");
        body.removeAllViews();
        titleSub.setText("成绩单");

        body.addView(scoreView(score, passed));
        TextView c = label(comment, 13, C_TXT2, false);
        c.setLineSpacing(dp(5), 1f);
        c.setPadding(dp(4), dp(14), dp(4), dp(6));
        body.addView(c);

        // 成绩单上也标出来源：这一局是从哪条素材衍生出来的
        String fromRes = res.optString("from", "");
        if (fromRes == null || fromRes.length() == 0) fromRes = fromText;
        if (fromRes != null && fromRes.length() > 0) {
            LinearLayout fr = new LinearLayout(this);
            fr.setBackground(roundRect(0xFF0f1a2b, C_ACCENT, 0));
            fr.setPadding(dp(11), dp(8), dp(11), dp(8));
            fr.addView(label("本局来自：" + fromRes, 11, C_TXT2, false));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.topMargin = dp(6);
            flp.bottomMargin = dp(14);
            body.addView(fr, flp);
        }

        JSONArray wrong = res.optJSONArray("wrong");
        if (wrong != null && wrong.length() > 0) {
            body.addView(sectionTitle("错在哪"));
            for (int i = 0; i < wrong.length(); i++) {
                try {
                    JSONObject w = wrong.getJSONObject(i);
                    LinearLayout it = card();
                    it.setBackground(roundRect(0x1Aef4444, 0x55ef4444, 1));
                    it.addView(label("第 " + w.optInt("idx") + " 轮", 12, Color.parseColor("#fca5a5"), true));
                    TextView d = label("你选了「" + w.optString("chose") + "」\n应该「" + w.optString("right") + "」\n" + w.optString("tip"), 13, C_TXT, false);
                    d.setLineSpacing(dp(3), 1f);
                    d.setPadding(0, dp(6), 0, 0);
                    it.addView(d);
                    body.addView(it);
                } catch (Exception ignore) { }
            }
        } else {
            body.addView(label("四步全守住了，没有失守环节。", 13, Color.parseColor("#86efac"), false));
        }

        addResultButtons();
    }

    private void renderResultLocal() {
        int score = Math.round(correctCount * 100f / total);
        boolean passed = score >= 75;
        body.removeAllViews();
        titleSub.setText("成绩单（本地）");
        body.addView(scoreView(score, passed));
        String comment;
        if (score == 100) comment = "四步全守住了。骗子换招你都看穿了，这就是我们想要的效果。";
        else if (passed) comment = "基本守住了，但还有一步会被牵着走。记住：只要让你别告诉家人，就是骗子。";
        else comment = "这一局比较危险。不是您不小心，是骗子的话术就是照着人的弱点设计的。多练一局会好很多。";
        TextView c = label(comment, 13, C_TXT2, false);
        c.setLineSpacing(dp(5), 1f);
        c.setPadding(dp(4), dp(14), dp(4), dp(6));
        body.addView(c);
        addResultButtons();
    }

    private View scoreView(int score, boolean passed) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(10), 0, 0);

        int color = score >= 75 ? C_OK : (score >= 50 ? C_WARN : C_BAD);
        TextView n = label(String.valueOf(score), 52, color, true);
        n.setGravity(Gravity.CENTER);
        box.addView(n);
        TextView u = label("分 · 及格线 75", 11, C_TXT3, false);
        u.setGravity(Gravity.CENTER);
        box.addView(u);
        TextView v = label(passed ? "过关了" : "这一局没守住", 16, passed ? Color.parseColor("#4ade80") : Color.parseColor("#f87171"), true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(10), 0, 0);
        box.addView(v);
        return box;
    }

    private void addResultButtons() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(20), 0, 0);

        Button again = new Button(this);
        again.setText("再练一局");
        again.setAllCaps(false);
        again.setTextColor(Color.parseColor("#04121f"));
        again.setBackground(roundRect(C_ACCENT, C_ACCENT, 0));
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startDrill(scenarioKey); }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp1.rightMargin = dp(10);
        row.addView(again, lp1);

        Button home = new Button(this);
        home.setText("返回");
        home.setAllCaps(false);
        home.setTextColor(C_TXT);
        home.setBackground(roundRect(C_PANEL2, C_LINE, 1));
        home.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showHome(); }
        });
        row.addView(home, new LinearLayout.LayoutParams(0, dp(48), 1f));

        body.addView(row);
    }

    // ============================================================================
    //  服务器地址设置
    // ============================================================================

    private void showServerDialog() {
        final EditText et = new EditText(this);
        et.setHint(DrillNet.DEFAULT_BASE);
        et.setText(DrillNet.base(this));
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        new AlertDialog.Builder(this)
            .setTitle("云端服务地址")
            .setMessage("默认已连云端（华为云 ECS）。如需改回局域网调试，填电脑的局域网 IP。\n清空则使用默认云端地址。")
            .setView(et)
            .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    DrillNet.setBase(DrillActivity.this, et.getText().toString());
                    Toast.makeText(DrillActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                    showHome();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ============================================================================
    //  工具
    // ============================================================================

    private void ui(Runnable r) { runOnUiThread(r); }

    private void postDelayed(Runnable r, long ms) {
        body.postDelayed(r, ms);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(13), dp(14), dp(13));
        c.setBackground(roundRect(C_PANEL, C_LINE, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = label(s, 11, C_TXT3, true);
        t.setPadding(dp(2), dp(14), 0, dp(8));
        return t;
    }

    private GradientDrawable roundRect(int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private Round parseRound(JSONObject o) {
        Round r = new Round();
        if (o == null) { r.ai = ""; r.opts = new String[]{"—"}; r.ok = 0; r.tip = ""; return r; }
        r.stage = o.optInt("stage", 1);
        r.ai = o.optString("aiText", "");
        r.question = o.optString("question", "");
        JSONArray arr = o.optJSONArray("choices");
        if (arr == null || arr.length() == 0) {
            r.opts = new String[]{"—"};
        } else {
            r.opts = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) r.opts[i] = arr.optString(i, "");
        }
        r.ok = 0;   // 云端不给正确答案，判对错由服务端返回
        return r;
    }

    private String nextText(JSONObject p) {
        if (!p.optBoolean("enabled", true) || "off".equals(p.optString("freq"))) return "已关闭";
        String iso = p.optString("nextAt", "");
        if (iso == null || iso.length() < 16) return "未排期";
        try {
            // 形如 2026-11-02T02:00:00.000Z，转本地时间
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(iso.substring(0, 19));
            java.text.SimpleDateFormat g = new java.text.SimpleDateFormat("M月d日 EEEE HH:mm", java.util.Locale.CHINA);
            long days = (d.getTime() - System.currentTimeMillis()) / 86400000L;
            return g.format(d) + (days > 0 ? "（" + days + " 天后）" : "（今天）");
        } catch (Exception e) {
            return iso;
        }
    }

    private String freqText(String f) {
        if ("weekly".equals(f)) return "每周一次";
        if ("biweekly".equals(f)) return "每两周一次";
        if ("monthly".equals(f)) return "每月一次";
        return "已关闭";
    }

    private Scenario findScenario(String key) {
        for (int i = 0; i < SC.length; i++) if (SC[i].key.equals(key)) return SC[i];
        return null;
    }

    // ============================================================================
    //  数据：内置剧本（断网兜底，也是上云前就能演示的底气）
    // ============================================================================

    static class Round {
        int stage;
        String ai;
        String question;
        String[] opts;
        int ok;
        String tip;
    }

    static class Scenario {
        String key, name, icon, desc;
        Round[] rounds;
    }

    private static Round R(int stage, String ai, String q, int ok, String tip, String... opts) {
        Round r = new Round();
        r.stage = stage; r.ai = ai; r.question = q; r.ok = ok; r.tip = tip; r.opts = opts;
        return r;
    }

    private static Scenario S(String key, String name, String icon, String desc, Round... rs) {
        Scenario s = new Scenario();
        s.key = key; s.name = name; s.icon = icon; s.desc = desc; s.rounds = rs;
        return s;
    }

    static final Scenario[] SC = new Scenario[]{
        S("gongan", "冒充公检法", "👮", "最常见的\"洗钱案\"套路，专挑老人下手",
            R(1, "您好，这里是北京市公安局刑侦支队。请问是您本人吗？我们有一个案子需要向您核实一下情况。",
                "对方自称是公安局的", 2, "真警察不会打电话要你配合\"资金核查\"。挂掉，自己拨 110 问，是最稳的一步。",
                "是，我就是，什么事？", "你是谁？警号多少？", "挂了，我自己打 110 问"),
            R(2, "您名下尾号 6688 的银行卡涉嫌一起特大洗钱案件，我们已经立案侦查。现在依法要冻结您名下所有账户。",
                "对方说你涉嫌犯罪", 1, "一被吓住就输了。真立案会有书面文书，不会在电话里通知你。",
                "我没干过，那我该怎么办？", "你凭什么说我犯罪？有文件吗？", "我马上配合你们"),
            R(3, "这个案子涉密，您现在不能告诉任何人，包括您的子女，否则视为串供。",
                "对方要求你别告诉家人", 1, "\"别告诉家人\"是所有诈骗的共同点。凡是让你瞒着子女的，一定是骗子。",
                "好，我不说", "我现在就给我女儿打电话", "那我先想想"),
            R(4, "为了证明您的资金清白，请把存款转入我们的国家安全账户接受审查，核查无误后 24 小时内原路退回。",
                "对方让你把钱转到\"安全账户\"", 1, "根本不存在\"安全账户\"。只要听到转账、验资、审查资金，就是诈骗。",
                "好，我现在去银行转", "不转，我先问家里人", "那我先转一部分试试")),

        S("qinshu", "冒充子女换号", "📱", "\"妈我换号了\"——利用的是亲情",
            R(1, "妈，我手机掉水里坏了，这是同事的号，我新号还没办下来。你存一下这个号。",
                "对方自称是你的孩子", 1, "声音可以模仿，名字可以打听到。要问只有你家人知道的事。",
                "哎，你怎么了？没事吧", "你叫什么名字？我儿子叫什么？", "哦，那我存一下"),
            R(2, "妈，我这边出事了，跟人起了冲突，对方要赔钱，不然就要报警抓我。我现在急得不行。",
                "对方说自己出事了", 1, "越是催你、越是不让你核实的，越可疑。挂掉，用原来的号码打回去。",
                "要多少钱？妈给你", "你先别急，我打你原来那个号", "你怎么这么不小心"),
            R(3, "妈你别跟我爸说，也别跟姐姐说，他们知道了肯定骂我。你现在就帮我。",
                "对方让你别告诉其他家人", 1, "骗子最怕你去问别人。只要对方要求保密，就先当他是骗子。",
                "好，我不说，妈帮你", "不行，这么大的事必须告诉你爸", "那你自己小心点"),
            R(4, "妈你先转 3 万到这个卡上，是我朋友的账户，我自己的卡被冻结了用不了。今天下午 5 点前必须给。",
                "对方要你转钱到别人卡上", 1, "转到\"朋友账户\"是典型特征。视频一下、或者打原来号码，一问就穿。",
                "好，我这就去转", "不转，我先视频看看你", "那我转 2 万行不行")),

        S("kefu", "冒充客服退款", "📦", "说要给你退款，其实是要你的验证码",
            R(1, "您好，我是淘宝客服。您上周买的电饭锅有质量问题，厂家要给您三倍赔付。",
                "对方说要给你赔钱", 1, "主动给你钱的，九成是骗子。退款只会在你自己下单的 App 里办。",
                "真的？怎么赔？", "我自己去 App 里看订单", "那太好了"),
            R(2, "退款要转到您绑定的银行卡上。但您的账户有风险，需要先配合做一次资金验证。",
                "对方说要先\"验证资金\"", 1, "退款只需要你的账号，永远不会问你有多少钱。",
                "怎么验证？", "退款为什么要验我的余额？", "那你教我"),
            R(3, "这个流程比较特殊，您别挂电话，也别跟家里人说，他们不懂会打断您操作。",
                "对方不让你挂电话", 1, "\"别挂电话、别问家人\"是为了不给你冷静和商量的机会。拉断是安全的。",
                "好，你说", "我先挂了，问问我女儿", "那你快点"),
            R(4, "我现在给您手机上发一个验证码，您念给我，我帮您在系统里走完最后一步。",
                "对方要你念短信验证码", 1, "验证码等于你银行卡的钥匙。任何人问你要，都是骗子。",
                "好，是 382910", "验证码不能给任何人", "我念慢一点")),

        S("shuadan", "刷单返利", "💰", "先让你赚几十块，再骗走几万",
            R(1, "阿姨您好，我们是做电商推广的，帮商家点个好评就能拿佣金，一单 8 块，一天能有百来块。",
                "对方说动动手就能赚钱", 1, "\"动动手就赚钱\"本身就是最大的破绽。真工作不会在微信群里招人。",
                "有兴趣，怎么做？", "不用出钱吧？", "一天能赚多少？"),
            R(2, "第一单已经给您返了 8 块，想继续做要升级成会员，接的单佣金更高。",
                "对方让你先交钱升级", 1, "所有刷单都是同一个套路：先给甜头，再让你交本金。",
                "多少钱？", "要交钱就不做了", "那交一点试试"),
            R(3, "您这个情况比较特殊，别跟家里人说，说了他们肯定不同意。",
                "对方让你瞒着家人", 1, "让你保密的人，一定不希望你被提醒。这本身就是最大的警报。",
                "也是", "我跟我儿子商量一下", "那我先不说"),
            R(4, "刚才您操作错了，账户被冻结，要再充 2 万才能解冻，不然前面的钱都拿不回来。",
                "对方让你再充钱解冻", 1, "冒充\"解冻费\"是最后一刀。此时止损，就是最好的选择。",
                "那我再充 2 万", "不充了，这是骗局", "能不能少一点")),
    };
}
