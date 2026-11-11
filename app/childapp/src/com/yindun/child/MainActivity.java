package com.yindun.child;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子女端主界面
 * ============================================================
 * 三秒内回答"我妈现在有没有事"：
 *   顶部深色汇总卡 —— 最近有几条高风险、最近一条是什么，一键打给妈妈
 *   中间分类      —— 全部 / 电话 / 短信 / 微信
 *   时间线        —— 每条提醒带等级、骗局进度（第几步）、对方原话，点进去看详情
 *
 * 视觉与老人端同一套：冷白底 + 深海军蓝安全核心 + 盾牌圆环，
 * 颜色只表达风险（红 / 橙 / 蓝 / 绿）。
 * ============================================================
 */
public class MainActivity extends Activity implements Hub.Listener {

    // ---------- 配色（与老人端、效果图一致）----------
    private static final int BG = 0xFFF3F5F8, CARD = 0xFFFFFFFF, LINE = 0xFFE4E8EE, LINE2 = 0xFFDDE2EA;
    private static final int INK = 0xFF0B1220, INK2 = 0xFF344054, INK3 = 0xFF667085, NAVY = 0xFF0A1A33;
    private static final int BLUE = 0xFF2B6CF6, OK = 0xFF0F7A4A, OK_DOT = 0xFF12B76A;
    private static final int RED = 0xFFB42318, RED_BG = 0xFFFEE4E2, AMB = 0xFFB54708, AMB_BG = 0xFFFEF0C7;
    private static final int SKY = 0xFF175CD3, SKY_BG = 0xFFEFF4FF;
    private static final int[] STAGE_COLOR = {0xFFF79009, 0xFFF97066, 0xFFF04438, 0xFFB42318};
    private static final String[] STAGE_NAME = {"", "建立信任", "制造恐慌", "隔离家人", "索要钱款"};

    private FrameLayout host;          // 首页 / 详情 两个页面在这里切换
    private View homePage;
    private View detailPage;

    private TextView tvName, tvState, tvAvatar;
    private View dotState;
    private TextView heroNum, heroUnit, heroSub, statTotal, statHigh, statLast;
    private RingView heroRings;
    private Alert heroAlert;
    private LinearLayout listBox;
    private View pairBanner;
    private final TextView[] tabs = new TextView[4];
    private String filter = "全部";
    private List<Alert> last = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Conf.get().init(this);
        Notify.init(this);

        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        host = new FrameLayout(this);
        host.setBackgroundColor(BG);
        homePage = buildHome();
        host.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        setContentView(host);

        askNotificationPermission();

        // 起前台服务：保住进程，SSE 才能在后台活着
        Intent svc = new Intent(this, WatchService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);

        Hub.get().start(this);
        Hub.get().addListener(this);
    }

    @Override
    protected void onDestroy() {
        Hub.get().removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderState(Hub.get().state());
        refreshName();
    }

    @Override
    public void onBackPressed() {
        if (detailPage != null) { closeDetail(); return; }
        super.onBackPressed();
    }

    // ==========================================================================
    //  首页
    // ==========================================================================

    private View buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        LinearLayout col = vbox();
        col.setPadding(dp(16), dp(8), dp(16), dp(28));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---------- 顶栏：头像 + 称呼 + 连接状态 ｜ 设置 ----------
        LinearLayout head = hbox();
        head.setMinimumHeight(dp(56));
        FrameLayout av = new FrameLayout(this);
        tvAvatar = text("妈", 18, Color.WHITE, true);
        tvAvatar.setGravity(Gravity.CENTER);
        tvAvatar.setBackground(round(NAVY, 22));
        av.addView(tvAvatar, new FrameLayout.LayoutParams(dp(44), dp(44)));
        dotState = new View(this);
        GradientDrawable dg = round(OK_DOT, 7);
        dg.setStroke(dp(2), BG);
        dotState.setBackground(dg);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(14), dp(14));
        dlp.gravity = Gravity.END | Gravity.BOTTOM;
        av.addView(dotState, dlp);
        head.addView(av, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout who = vbox();
        LinearLayout nameRow = hbox();
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        tvName = text("妈妈", 19, INK, true);
        nameRow.addView(tvName);
        TextView caret = text("▾", 13, INK3, true);
        caret.setPadding(dp(6), dp(5), 0, 0);
        nameRow.addView(caret);
        who.addView(nameRow);
        tvState = text("连接中", 13, INK3, false);
        who.addView(tvState);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1f);
        wlp.leftMargin = dp(12);
        head.addView(who, wlp);
        // 点称呼 = 换一位老人看。子女端常常要同时照看爸妈两边，
        // 所以这个入口必须一眼能找到，不能藏在设置里。
        View.OnClickListener pick = new View.OnClickListener() {
            @Override public void onClick(View v) { showElders(); }
        };
        who.setOnClickListener(pick);
        av.setOnClickListener(pick);
        TextView set = text("设置", 15, INK, true);
        set.setGravity(Gravity.CENTER);
        GradientDrawable sb = round(CARD, 22);
        sb.setStroke(dp(1), LINE2);
        set.setBackground(sb);
        set.setPadding(dp(16), 0, dp(16), 0);
        set.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        head.addView(set, new LinearLayout.LayoutParams(-2, dp(44)));
        col.addView(head);

        // ---------- 未配对提示 ----------
        LinearLayout pb = vbox();
        GradientDrawable pbg = round(SKY_BG, 18);
        pbg.setStroke(dp(1), 0xFFC7D7FE);
        pb.setBackground(pbg);
        pb.setPadding(dp(16), dp(14), dp(16), dp(14));
        pb.addView(text("尚未与老人手机配对", 16, INK, true));
        TextView pbt = text("当前显示的是公开演示数据。请在老人手机「银盾 → 设置 → 初始设置向导」中获取 6 位配对码。", 14, INK2, false);
        pbt.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams pbtl = new LinearLayout.LayoutParams(-1, -2);
        pbtl.topMargin = dp(4);
        pb.addView(pbt, pbtl);
        TextView pbBtn = text("输入配对码", 16, Color.WHITE, true);
        pbBtn.setGravity(Gravity.CENTER);
        pbBtn.setBackground(round(NAVY, 12));
        pbBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPair(); }
        });
        LinearLayout.LayoutParams pbbl = new LinearLayout.LayoutParams(-1, dp(46));
        pbbl.topMargin = dp(12);
        pb.addView(pbBtn, pbbl);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(-1, -2);
        pbl.topMargin = dp(10);
        col.addView(pb, pbl);
        pairBanner = pb;

        // ---------- 深色汇总卡 ----------
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(NAVY);
        hbg.setCornerRadius(dp(24));
        hero.setBackground(hbg);
        hero.setClipToOutline(true);
        heroRings = new RingView(this);
        hero.addView(heroRings, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout hc = vbox();
        hc.setPadding(dp(18), dp(18), dp(18), dp(16));
        hero.addView(hc, new FrameLayout.LayoutParams(-1, -2));
        TextView lab = text("风险概览", 13, 0xFF8FA3C4, false);
        lab.setLetterSpacing(0.15f);
        hc.addView(lab);
        LinearLayout numRow = hbox();
        numRow.setGravity(Gravity.BOTTOM);
        heroNum = text("0", 40, 0xFFFDA29B, true);
        heroNum.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        heroNum.setIncludeFontPadding(false);
        numRow.addView(heroNum);
        heroUnit = text("条高风险", 20, Color.WHITE, true);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.leftMargin = dp(6);
        ulp.bottomMargin = dp(3);
        numRow.addView(heroUnit, ulp);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(6);
        hc.addView(numRow, nlp);
        heroSub = text("正在同步数据…", 15, 0xFFC3CFE2, false);
        heroSub.setLineSpacing(dp(3), 1f);
        heroSub.setMaxLines(2);
        LinearLayout.LayoutParams hslp = new LinearLayout.LayoutParams(dp(240), -2);
        hslp.topMargin = dp(6);
        hc.addView(heroSub, hslp);

        LinearLayout hb = hbox();
        TextView callBtn = text("致电" + Conf.get().elderName(), 16, NAVY, true);
        callBtn.setTag("heroCall");
        callBtn.setGravity(Gravity.CENTER);
        callBtn.setBackground(round(Color.WHITE, 14));
        callBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { callElder(); }
        });
        hb.addView(callBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView detBtn = text("查看详情", 16, Color.WHITE, true);
        detBtn.setGravity(Gravity.CENTER);
        GradientDrawable db = round(0x00000000, 14);
        db.setStroke(dp(1), 0x4DFFFFFF);
        detBtn.setBackground(db);
        detBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (heroAlert != null) openDetail(heroAlert);
                else Toast.makeText(MainActivity.this, "暂无待处理的预警", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams dblp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        dblp.leftMargin = dp(10);
        hb.addView(detBtn, dblp);
        LinearLayout.LayoutParams hblp = new LinearLayout.LayoutParams(-1, -2);
        hblp.topMargin = dp(14);
        hc.addView(hb, hblp);

        View hr = new View(this);
        hr.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams hrlp = new LinearLayout.LayoutParams(-1, dp(1));
        hrlp.topMargin = dp(14);
        hc.addView(hr, hrlp);
        LinearLayout stats = hbox();
        statTotal = stat(stats, "预警总数");
        statHigh = stat(stats, "高风险");
        statLast = stat(stats, "最近预警");
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, -2);
        stlp.topMargin = dp(12);
        hc.addView(stats, stlp);
        LinearLayout.LayoutParams herolp = new LinearLayout.LayoutParams(-1, -2);
        herolp.topMargin = dp(10);
        col.addView(hero, herolp);

        // ---------- 分类 ----------
        LinearLayout seg = hbox();
        seg.setBackground(round(0xFFE8ECF2, 14));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] names = {"全部", "电话", "短信", "微信"};
        for (int i = 0; i < 4; i++) {
            final String n = names[i];
            TextView t = text(n, 14, INK2, false);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { filter = n; renderList(last); }
            });
            tabs[i] = t;
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i > 0) tlp.leftMargin = dp(4);
            seg.addView(t, tlp);
        }
        LinearLayout.LayoutParams seglp = new LinearLayout.LayoutParams(-1, -2);
        seglp.topMargin = dp(14);
        col.addView(seg, seglp);

        // ---------- 时间线 ----------
        listBox = vbox();
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(12);
        col.addView(listBox, llp);

        refreshName();
        return root;
    }

    private TextView stat(LinearLayout parent, String label) {
        LinearLayout c = vbox();
        TextView n = text("0", 19, Color.WHITE, true);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        c.addView(n);
        c.addView(text(label, 12, 0xFF8FA3C4, false));
        parent.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        return n;
    }

    private void refreshName() {
        if (pairBanner != null) pairBanner.setVisibility(Conf.get().paired() ? View.GONE : View.VISIBLE);
        String n = Conf.get().elderName();
        if (tvName != null) tvName.setText(n);
        if (tvAvatar != null) tvAvatar.setText(n.substring(0, 1));
        if (homePage != null) {
            View b = homePage.findViewWithTag("heroCall");
            if (b instanceof TextView) ((TextView) b).setText("致电" + n);
        }
    }

    // ==========================================================================
    //  数据回调（Hub 在后台线程回调，一律切回主线程）
    // ==========================================================================

    @Override
    public void onList(final List<Alert> list) {
        runOnUiThread(new Runnable() {
            @Override public void run() { last = new ArrayList<>(list); renderList(last); }
        });
    }

    @Override
    public void onNew(Alert a) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (listBox != null) {
                    listBox.setAlpha(0.35f);
                    listBox.animate().alpha(1f).setDuration(320).start();
                }
            }
        });
    }

    @Override
    public void onState(final String state) {
        runOnUiThread(new Runnable() {
            @Override public void run() { renderState(state); }
        });
    }

    private void renderState(String state) {
        if (tvState == null) return;
        if ("live".equals(state)) {
            dotState.setBackground(dot(OK_DOT));
            tvState.setText("守护中 · 实时连接");
            tvState.setTextColor(INK3);
            tvState.setOnClickListener(null);
        } else if ("connecting".equals(state)) {
            dotState.setBackground(dot(0xFFF79009));
            tvState.setText("正在连接云端…");
            tvState.setTextColor(AMB);
        } else {
            dotState.setBackground(dot(0xFFF04438));
            tvState.setText("连接已断开 · 轻触重新连接");
            tvState.setTextColor(RED);
            tvState.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Hub.get().restart(); }
            });
        }
    }

    private GradientDrawable dot(int c) {
        GradientDrawable g = round(c, 7);
        g.setStroke(dp(2), BG);
        return g;
    }

    private void renderList(List<Alert> list) {
        if (listBox == null) return;
        String name = Conf.get().elderName();

        // ---------- 汇总卡 ----------
        // 云端只给 HH:mm，判断不了"今天"，所以文案说"最近"不说"今天"
        int high = 0;
        heroAlert = null;
        for (Alert a : list) {
            if (a.rank() >= 3) {
                high++;
                if (heroAlert == null && !Conf.get().handled(a.id)) heroAlert = a;
            }
        }
        statTotal.setText(String.valueOf(list.size()));
        statHigh.setText(String.valueOf(high));
        statLast.setText(list.isEmpty() ? "--" : list.get(0).time);
        if (heroAlert != null) {
            heroNum.setVisibility(View.VISIBLE);
            heroNum.setText(String.valueOf(countOpen(list)));
            heroUnit.setText("条高风险待处理");
            heroSub.setText(heroAlert.time + " " + heroAlert.title);
            heroRings.setTone(0xF04438);
        } else {
            heroNum.setVisibility(View.GONE);
            heroUnit.setText(list.isEmpty() ? "状态正常" : "暂无待处理风险");
            heroSub.setText(list.isEmpty() ? name + "近期未发现可疑情况" : (high == 0 ? "近期无高风险预警，防护持续进行" : "高风险预警均已处理，防护持续进行"));
            heroRings.setTone(0x32D583);
        }

        // ---------- 分类按钮 ----------
        for (TextView t : tabs) {
            boolean on = t.getText().toString().equals(filter);
            t.setBackground(on ? round(CARD, 10) : null);
            t.setTextColor(on ? INK : INK2);
            t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }

        // ---------- 时间线 ----------
        listBox.removeAllViews();
        int shown = 0;
        for (Alert a : list) {
            if (!"全部".equals(filter) && !filter.equals(a.channelWord())) continue;
            if (shown == 0) {
                TextView sec = text("预警记录", 13, INK3, false);
                sec.setPadding(dp(4), 0, 0, dp(8));
                listBox.addView(sec);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(10);
            listBox.addView(card(a), lp);
            shown++;
        }
        if (shown == 0) {
            TextView empty = text(list.isEmpty()
                    ? "暂无预警记录。\n当" + name + "收到可疑短信、可疑来电，\n或在通话中进行转账操作时，将实时推送至此。"
                    : "该分类暂无预警记录", 14, INK3, false);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(dp(5), 1f);
            empty.setPadding(dp(10), dp(40), dp(10), dp(10));
            listBox.addView(empty);
        }
    }

    private int countOpen(List<Alert> list) {
        int n = 0;
        for (Alert a : list) if (a.rank() >= 3 && !Conf.get().handled(a.id)) n++;
        return n;
    }

    /** 一条提醒：等级 + 来源 + 时间 / 标题 / 骗局进度 / 原话 / 处理 */
    private View card(final Alert a) {
        LinearLayout c = vbox();
        GradientDrawable bg = round(CARD, 20);
        bg.setStroke(dp(1), LINE);
        c.setBackground(bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        boolean done = Conf.get().handled(a.id);
        if (done) c.setAlpha(0.6f);

        LinearLayout r1 = hbox();
        r1.addView(badge(a));
        TextView src = text(source(a), 13, INK3, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1f);
        slp.leftMargin = dp(8);
        r1.addView(src, slp);
        r1.addView(text(a.time, 13, INK3, false));
        c.addView(r1);

        TextView title = text(a.title.length() > 0 ? a.title : "可疑情况", 17, INK, true);
        title.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        c.addView(title, tlp);

        int st = stageOf(a);
        if (st > 0) {
            LinearLayout sr = hbox();
            sr.addView(stageBar(st, dp(92), dp(5)));
            TextView sw = text("骗局进度 第 " + st + " 步 · " + STAGE_NAME[st], 14, st >= 3 ? RED : AMB, true);
            LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(-2, -2);
            swlp.leftMargin = dp(10);
            sr.addView(sw, swlp);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-1, -2);
            srlp.topMargin = dp(10);
            c.addView(sr, srlp);
        }

        List<String> qs = quotes(a.body);
        String plain = stripQuotes(a.body);
        if (plain.length() > 0) {
            TextView b = text(plain, 14, INK2, false);
            b.setLineSpacing(dp(3), 1f);
            b.setMaxLines(3);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = dp(8);
            c.addView(b, blp);
        }
        for (int i = 0; i < qs.size() && i < 2; i++) {
            TextView q = text("“" + qs.get(i) + "”", 13, INK2, false);
            q.setBackground(round(BG, 8));
            q.setPadding(dp(9), dp(5), dp(9), dp(5));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -2);
            qlp.topMargin = dp(6);
            c.addView(q, qlp);
        }
        if (a.summary != null && a.summary.length() > 0) {
            TextView sm = text("AI 摘要：" + a.summary, 13, SKY, false);
            sm.setBackground(round(SKY_BG, 10));
            sm.setPadding(dp(10), dp(8), dp(10), dp(8));
            sm.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams smlp = new LinearLayout.LayoutParams(-1, -2);
            smlp.topMargin = dp(8);
            c.addView(sm, smlp);
        }

        if (a.rank() >= 2) {
            TextView go = text(done ? "已处理" : "查看并处理 ›", 14, done ? OK : BLUE, true);
            go.setGravity(Gravity.END);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
            glp.topMargin = dp(8);
            c.addView(go, glp);
        }
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDetail(a); }
        });
        return c;
    }

    private TextView badge(Alert a) {
        int fg, bg;
        String w;
        if (a.rank() >= 3) { fg = RED; bg = RED_BG; w = "block".equals(a.level) && !"通话复盘".equals(source(a)) ? "已拦截" : "高风险"; }
        else if (a.rank() == 2) { fg = AMB; bg = AMB_BG; w = "中风险"; }
        else { fg = SKY; bg = SKY_BG; w = "低风险"; }
        TextView t = text(w, 12, fg, true);
        t.setBackground(round(bg, 8));
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        return t;
    }

    private String source(Alert a) {
        if (a.why != null && a.why.startsWith("通话录音复盘")) return "通话复盘";
        return a.channelWord();
    }

    // ==========================================================================
    //  详情页
    // ==========================================================================

    private void openDetail(final Alert a) {
        if (detailPage != null) host.removeView(detailPage);
        String name = Conf.get().elderName();

        LinearLayout root = vbox();
        root.setBackgroundColor(BG);
        root.setClickable(true);

        LinearLayout bar = hbox();
        bar.setPadding(dp(6), 0, dp(16), 0);
        TextView back = text("‹", 30, INK, false);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDetail(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(56)));
        bar.addView(text("预警详情", 17, INK, true));
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = vbox();
        col.setPadding(dp(16), 0, dp(16), dp(20));
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---------- 概要 ----------
        LinearLayout sum = panel();
        LinearLayout r1 = hbox();
        r1.addView(badge(a));
        TextView src = text(source(a), 12, INK2, true);
        src.setBackground(round(0xFFF2F4F7, 8));
        src.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = dp(8);
        r1.addView(src, slp);
        sum.addView(r1);
        TextView title = text(a.title.length() > 0 ? a.title : "可疑情况", 20, INK, true);
        title.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(12);
        sum.addView(title, tlp);
        TextView meta = text("时间 " + a.time + "   ·   来源 " + source(a), 13, INK3, false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(6);
        sum.addView(meta, mlp);

        int st = stageOf(a);
        if (st > 0) {
            View hr = new View(this);
            hr.setBackgroundColor(0xFFEEF1F5);
            LinearLayout.LayoutParams hrlp = new LinearLayout.LayoutParams(-1, dp(1));
            hrlp.topMargin = dp(14);
            sum.addView(hr, hrlp);
            TextView sl = text("骗局进度", 13, INK3, false);
            LinearLayout.LayoutParams sllp = new LinearLayout.LayoutParams(-1, -2);
            sllp.topMargin = dp(12);
            sum.addView(sl, sllp);
            LinearLayout grid = hbox();
            for (int i = 1; i <= 4; i++) {
                LinearLayout cell = vbox();
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                View b = new View(this);
                b.setBackground(round(i <= st ? STAGE_COLOR[i - 1] : LINE, 3));
                cell.addView(b, new LinearLayout.LayoutParams(-1, dp(6)));
                TextView l = text(i + " " + STAGE_NAME[i], 12, i == st ? RED : 0xFF475467, i == st);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
                llp.topMargin = dp(6);
                cell.addView(l, llp);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (i > 1) clp.leftMargin = dp(6);
                grid.addView(cell, clp);
            }
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
            glp.topMargin = dp(8);
            sum.addView(grid, glp);
        }
        col.addView(sum, panelLp());

        // ---------- 发生了什么 ----------
        List<String> qs = quotes(a.body);
        String plain = stripQuotes(a.body);
        if (plain.length() > 0 || !qs.isEmpty() || (a.summary != null && a.summary.length() > 0)) {
            LinearLayout what = panel();
            what.addView(text("事件详情", 15, INK, true));
            if (a.summary != null && a.summary.length() > 0) {
                TextView sm = text("AI 摘要：" + a.summary, 15, SKY, false);
                sm.setLineSpacing(dp(3), 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(8);
                what.addView(sm, lp);
            }
            if (plain.length() > 0) {
                TextView b = text(plain, 15, INK2, false);
                b.setLineSpacing(dp(4), 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(8);
                what.addView(b, lp);
            }
            for (String q : qs) {
                TextView qt = text("“" + q + "”", 14, INK, false);
                qt.setBackground(round(BG, 10));
                qt.setPadding(dp(10), dp(8), dp(10), dp(8));
                qt.setLineSpacing(dp(2), 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(6);
                what.addView(qt, lp);
            }
            col.addView(what, panelLp());
        }

        // ---------- 判定依据 ----------
        if (a.why != null && a.why.trim().length() > 0) {
            LinearLayout why = panel();
            why.addView(text("判定依据", 15, INK, true));
            FlowRow chips = new FlowRow(this, dp(6));
            for (String part : a.why.split("\\s*[·、+]\\s*")) {
                if (part.trim().length() == 0) continue;
                TextView ch = text(part.trim(), 12, SKY, false);
                ch.setBackground(round(SKY_BG, 8));
                ch.setPadding(dp(8), dp(3), dp(8), dp(3));
                chips.addView(ch);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            why.addView(chips, lp);
            col.addView(why, panelLp());
        }

        // ---------- 建议 ----------
        LinearLayout tip = panel();
        tip.addView(text("处置建议", 15, INK, true));
        String[] steps = a.rank() >= 3
                ? new String[]{"立即致电" + name + "，确认未转账、未透露验证码",
                "提醒" + name + "：公检法机关与银行不会通过电话要求转账",
                "如资金已转出，请立即拨打 96110 反诈专线"}
                : new String[]{"致电" + name + "，了解来电情况",
                "提醒" + name + "：陌生人提出涉及钱款的要求，应先挂断并与家人核实",
                "如无法判断，可拨打 96110 反诈专线咨询"};
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = hbox();
            row.setGravity(Gravity.TOP);
            row.setMinimumHeight(dp(26));   // 单行时也给序号圆点留够高度
            TextView n = text(String.valueOf(i + 1), 12, Color.WHITE, true);
            n.setGravity(Gravity.CENTER);
            n.setBackground(round(NAVY, 11));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(22), dp(22));
            nlp.topMargin = dp(1);
            row.addView(n, nlp);
            TextView s = text(steps[i], 15, INK, false);
            s.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, -2, 1f);
            slp2.leftMargin = dp(10);
            row.addView(s, slp2);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = dp(10);
            tip.addView(row, rlp);
        }
        col.addView(tip, panelLp());

        if (a.why != null && a.why.contains("转写来源")) {
            TextView note = text("通话录音已在云端删除，仅保留上述关键语句", 12, 0xFF98A2B3, false);
            note.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(12);
            col.addView(note, lp);
        }

        // ---------- 底部操作 ----------
        LinearLayout act = hbox();
        act.setBackgroundColor(CARD);
        act.setPadding(dp(16), dp(12), dp(16), dp(16));
        TextView call = text("致电" + name, 16, Color.WHITE, true);
        call.setGravity(Gravity.CENTER);
        call.setBackground(round(NAVY, 14));
        call.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { callElder(); }
        });
        act.addView(call, new LinearLayout.LayoutParams(0, dp(50), 2f));
        final boolean done = Conf.get().handled(a.id);
        TextView mark = text(done ? "撤销处理" : "标记已处理", 15, INK, true);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable mb = round(CARD, 14);
        mb.setStroke(dp(1), LINE2);
        mark.setBackground(mb);
        mark.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Conf.get().handled(a.id, !done);
                closeDetail();
                renderList(last);
            }
        });
        LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        mlp2.leftMargin = dp(10);
        act.addView(mark, mlp2);
        View top = new View(this);
        top.setBackgroundColor(LINE);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(1)));
        root.addView(act, new LinearLayout.LayoutParams(-1, -2));

        detailPage = root;
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));
        root.setTranslationX(getResources().getDisplayMetrics().widthPixels);
        root.animate().translationX(0).setDuration(220).start();
    }

    private void closeDetail() {
        final View v = detailPage;
        detailPage = null;
        if (v == null) return;
        v.animate().translationX(getResources().getDisplayMetrics().widthPixels).setDuration(200)
                .withEndAction(new Runnable() {
                    @Override public void run() { host.removeView(v); }
                }).start();
    }

    private LinearLayout panel() {
        LinearLayout p = vbox();
        GradientDrawable g = round(CARD, 20);
        g.setStroke(dp(1), LINE);
        p.setBackground(g);
        p.setPadding(dp(16), dp(16), dp(16), dp(16));
        return p;
    }

    private LinearLayout.LayoutParams panelLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        return lp;
    }

    private void callElder() {
        String p = Conf.get().elderPhone();
        if (p.length() < 5) {
            Toast.makeText(this, "请先在「设置」中填写" + Conf.get().elderName() + "的手机号码", Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        // 用拨号盘而不是直接拨出：子女端不需要电话权限，也让子女自己按下拨号键
        startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p)));
    }

    // ==========================================================================
    //  解析：阶段、原话
    // ==========================================================================

    private static final Pattern STAGE = Pattern.compile("S([1-4])");
    private static final Pattern QUOTE = Pattern.compile("「([^」]{2,60})」");

    /** 从判据里认出"第几步"（云端写成 "S4要钱" 这种） */
    private int stageOf(Alert a) {
        Matcher m = STAGE.matcher(a.why == null ? "" : a.why);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private List<String> quotes(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = QUOTE.matcher(body == null ? "" : body);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private String stripQuotes(String body) {
        if (body == null) return "";
        return QUOTE.matcher(body).replaceAll("").replaceAll("\\s+$", "").trim();
    }

    // ==========================================================================
    //  设置
    // ==========================================================================

    private void showSettings() {
        LinearLayout box = vbox();
        box.setPadding(dp(22), dp(8), dp(22), dp(8));

        final EditText edName = field(box, "老人称呼（如：妈妈、爸爸）", Conf.get().elderName(), InputType.TYPE_CLASS_TEXT);
        final EditText edPhone = field(box, "老人手机号码（用于一键致电）", Conf.get().elderPhone(), InputType.TYPE_CLASS_PHONE);
        final EditText edServer = field(box, "服务器地址（如 http://1.2.3.4:8080）", Conf.get().server(), InputType.TYPE_TEXT_VARIATION_URI);
        TextView pairState = text(pairedText(), 13, Conf.get().paired() ? OK : AMB, true);
        LinearLayout.LayoutParams psl = new LinearLayout.LayoutParams(-2, -2);
        psl.topMargin = dp(14);
        box.addView(pairState, psl);

        final CheckBox cbSound = new CheckBox(this);
        cbSound.setText("收到预警时响铃并振动");
        cbSound.setChecked(Conf.get().sound());
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(-2, -2);
        cblp.topMargin = dp(12);
        box.addView(cbSound, cblp);

        TextView note = text("保存后将自动重新连接。当前连接状态：" + Hub.get().state(), 12, INK3, false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(8);
        box.addView(note, nlp);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(sv)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Conf.get().elderName(edName.getText().toString());
                        Conf.get().elderPhone(edPhone.getText().toString().replaceAll("\\s+", ""));
                        Conf.get().server(edServer.getText().toString());
                        Conf.get().sound(cbSound.isChecked());
                        Hub.get().restart();
                        refreshName();
                        renderList(last);
                        Toast.makeText(MainActivity.this, "已保存，正在重新连接", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(Conf.get().paired() ? "重新配对" : "配对", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { showPair(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 输入老人手机上显示的 6 位配对码 */
    private void showPair() {
        LinearLayout box = vbox();
        box.setPadding(dp(22), dp(8), dp(22), dp(8));
        TextView tip = text("请在老人手机「银盾 → 设置 → 初始设置向导 → 子女端配对」中获取配对码，10 分钟内有效。", 14, INK2, false);
        tip.setLineSpacing(dp(3), 1f);
        box.addView(tip);
        final EditText ed = new EditText(this);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER);
        ed.setTextSize(26);
        ed.setLetterSpacing(0.3f);
        ed.setGravity(Gravity.CENTER);
        ed.setHint("6 位数字");
        ed.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        box.addView(ed, lp);
        new AlertDialog.Builder(this)
                .setTitle("与老人手机配对")
                .setView(box)
                .setPositiveButton("配对", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        final String code = ed.getText().toString().trim();
                        if (code.length() != 6) {
                            Toast.makeText(MainActivity.this, "请输入 6 位配对码", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String err = Hub.pair(code);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (err == null) {
                                            final boolean isNew = Hub.lastPairIsNew;
                                            final String eid = Hub.lastPairElderId;
                                            Hub.get().restart();
                                            refreshName();
                                            if (isNew) {
                                                // 新绑定的老人云端只回 elderId 和 token，不知道该怎么称呼，
                                                // 所以立刻问一句 —— 界面上到处是称呼，先问清楚比事后去设置里改体面。
                                                Toast.makeText(MainActivity.this, "配对成功，正在同步预警记录", Toast.LENGTH_SHORT).show();
                                                askName(eid, Conf.get().elderName());
                                            } else {
                                                Toast.makeText(MainActivity.this, "已更新「" + Conf.get().elderName() + "」的配对凭证", Toast.LENGTH_SHORT).show();
                                            }
                                        } else {
                                            Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==========================================================================
    //  老人切换（一个子女照看两位老人）
    // ==========================================================================

    /** 面板：看哪位老人。点一行切过去，长按可以改名或解除绑定 */
    private void showElders() {
        final List<Conf.Elder> all = Conf.get().elders();
        final String cur = Conf.get().elderId();

        LinearLayout box = vbox();
        box.setPadding(dp(12), dp(4), dp(12), dp(4));

        final AlertDialog[] holder = new AlertDialog[1];

        for (int i = 0; i < all.size(); i++) {
            final Conf.Elder e = all.get(i);
            final boolean on = e.id.equals(cur);

            LinearLayout row = hbox();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(11), dp(10), dp(11));
            if (on) row.setBackground(round(SKY_BG, 14));

            TextView av = text(first(e.name), 16, Color.WHITE, true);
            av.setGravity(Gravity.CENTER);
            av.setBackground(round(on ? NAVY : 0xFF98A2B3, 20));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(40), dp(40));
            row.addView(av, alp);

            LinearLayout mid = vbox();
            mid.addView(text(e.name, 16, INK, on));
            String sub = e.isDemo()
                    ? "演示数据 · 还没和老人手机配对"
                    : "编号 " + e.shortId() + (e.token.length() > 0 ? " · 已配对" : " · 未配对");
            TextView sv = text(sub, 12, INK3, false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = dp(2);
            mid.addView(sv, slp);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
            mlp.leftMargin = dp(12);
            row.addView(mid, mlp);
            row.addView(text(on ? "当前" : "切换", 13, on ? SKY : INK3, true));

            if (!on) {
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (holder[0] != null) holder[0].dismiss();
                        switchElder(e.id);
                    }
                });
            }
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    manageElder(e.id, e.name, all.size() > 1);
                    return true;
                }
            });

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rlp.topMargin = dp(4);
            box.addView(row, rlp);
        }

        TextView tip = text("点一下就换成看这位老人；长按可以改称呼或解除绑定。", 12, INK3, false);
        tip.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(12);
        tlp.leftMargin = dp(10);
        box.addView(tip, tlp);

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("看哪位老人")
                .setView(box)
                .setPositiveButton("添加另一位", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { showPair(); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 切过去：换凭证 → 断开重连 → 清空列表 → 重新拉这一位的预警 */
    private void switchElder(String id) {
        if (!Conf.get().switchTo(id)) return;
        last = new ArrayList<>();
        renderList(last);
        refreshName();
        Hub.get().restart();
        Toast.makeText(this, "已切换到 " + Conf.get().elderName(), Toast.LENGTH_SHORT).show();
    }

    /** 长按管理：改名 / 解除绑定 */
    private void manageElder(final String id, final String name, final boolean canRemove) {
        final List<String> items = new ArrayList<>();
        items.add("改称呼");
        if (canRemove) items.add("解除绑定");
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) askName(id, name);
                        else confirmRemove(id, name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askName(final String id, String old) {
        final EditText ed = new EditText(this);
        ed.setText(old);
        ed.setSingleLine(true);
        ed.setInputType(InputType.TYPE_CLASS_TEXT);
        ed.setSelection(ed.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("怎么称呼这位老人")
                .setView(ed)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String v = ed.getText().toString().trim();
                        if (v.length() == 0) return;
                        Conf.get().name(id, v);
                        refreshName();
                        Toast.makeText(MainActivity.this, "以后就显示「" + v + "」", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemove(final String id, final String name) {
        new AlertDialog.Builder(this)
                .setTitle("解除与「" + name + "」的绑定？")
                .setMessage("解除后这位老人的预警就看不到了。想再看需要重新拿配对码。")
                .setPositiveButton("解除绑定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        boolean wasCurrent = id.equals(Conf.get().elderId());
                        if (!Conf.get().remove(id)) {
                            Toast.makeText(MainActivity.this, "至少要留一位老人", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (wasCurrent) {
                            last = new ArrayList<>();
                            renderList(last);
                            refreshName();
                            Hub.get().restart();
                        }
                        Toast.makeText(MainActivity.this, "已解除「" + name + "」", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String first(String s) {
        return s == null || s.length() == 0 ? "老" : s.substring(0, 1);
    }

    /** 设置里那行配对状态：说清"正在看谁、一共绑了几位" */
    private String pairedText() {
        if (!Conf.get().paired()) return "正在看：演示数据（还没和老人手机配对）";
        String s = "正在看：" + Conf.get().elderName() + "（编号 " + Conf.get().elderId() + "）";
        int n = Conf.get().elderCount();
        if (n > 1) s += "\n共绑定 " + n + " 位老人，点顶部称呼可切换";
        return s;
    }

    private EditText field(LinearLayout box, String label, String value, int type) {
        TextView t = text(label, 13, INK3, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(12);
        box.addView(t, lp);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(type);
        box.addView(e);
        return e;
    }

    // ==========================================================================
    //  小工具
    // ==========================================================================

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private View stageBar(int st, int w, int h) {
        LinearLayout l = hbox();
        for (int i = 1; i <= 4; i++) {
            View b = new View(this);
            b.setBackground(round(i <= st ? STAGE_COLOR[i - 1] : LINE, 3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
            if (i > 1) lp.leftMargin = dp(3);
            l.addView(b, lp);
        }
        l.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return l;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp((int) radiusDp));
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 汇总卡右上角的盾牌圆环（和老人端首页同一图形），颜色跟着风险变 */
    static class RingView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tone = 0x32D583;

        RingView(Context c) { super(c); }

        void setTone(int rgb) { tone = rgb; invalidate(); }

        /** 纯装饰：自己不要高度，跟着汇总卡的内容走（否则列表为空时会把卡片撑满整屏） */
        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), getSuggestedMinimumHeight());
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), cx = w - 52 * d, cy = 46 * d;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(w * 0.85f, 0, w * 0.75f,
                    new int[]{0xFF1C3F78, 0xFF0F2448, 0xFF0A1A33}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, getHeight(), p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(0x38000000 | tone);
            c.drawCircle(cx, cy, 85 * d, p);
            p.setColor(0x61000000 | tone);
            c.drawCircle(cx, cy, 55 * d, p);
        }
    }

    /** 自动换行的一排小标签 */
    static class FlowRow extends ViewGroup {
        private final int gap;

        FlowRow(Context c, int gap) { super(c); this.gap = gap; }

        @Override
        protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getSize(ws), x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + v.getMeasuredWidth() > max) { x = 0; y += lineH + gap; lineH = 0; }
                x += v.getMeasuredWidth() + gap;
                lineH = Math.max(lineH, v.getMeasuredHeight());
            }
            setMeasuredDimension(max, y + lineH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > max) { x = 0; y += lineH + gap; lineH = 0; }
                v.layout(x, y, x + w, y + h);
                x += w + gap;
                lineH = Math.max(lineH, h);
            }
        }
    }
}
