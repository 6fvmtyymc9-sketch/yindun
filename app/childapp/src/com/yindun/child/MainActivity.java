package com.yindun.child;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 子女端主界面。
 *
 * 布局只有三块：谁（妈妈）、今天怎么样（概览）、发生了什么（时间线）。
 * 刻意不做搜索、不做筛选、不做设置入口 —— 子女打开这个 App 的心情是着急的，
 * 她只需要三秒内知道「我妈现在有没有事」。
 */
public class MainActivity extends Activity implements Hub.Listener {

    // ---------- 配色（和云端子女端网页同一套，两台设备看着才像一家人）----------
    private static final int C_BG     = 0xFF0B1220;
    private static final int C_CARD   = 0xFF151F33;
    private static final int C_TEXT   = 0xFFE8EFF9;
    private static final int C_SUB    = 0xFF93A7C4;
    private static final int C_OK     = 0xFF2FE08A;
    private static final int C_WATCH  = 0xFF38BDF8;
    private static final int C_WARN   = 0xFFF59E0B;
    private static final int C_HIGH   = 0xFFF97316;
    private static final int C_BLOCK  = 0xFFEF4444;

    private TextView tvState;       // 顶栏右侧的连接状态
    private View dotState;          // 状态圆点
    private TextView tvOverview;    // 概览大数字
    private TextView tvOverviewSub;
    private LinearLayout listBox;   // 时间线容器

    private boolean firstRender = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Conf.get().init(this);
        Notify.init(this);

        setContentView(buildRoot());

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
    }

    // ==========================================================================
    //  界面骨架
    // ==========================================================================

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ---------- 顶栏 ----------
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(22), dp(26), dp(22), dp(14));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = new TextView(this);
        brand.setText("银盾 · 家人守护");
        brand.setTextColor(C_SUB);
        brand.setTextSize(13);
        brand.setLetterSpacing(0.08f);
        topRow.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dotState = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(6);
        dotState.setLayoutParams(dotLp);
        dotState.setBackground(round(C_OK, 4));
        topRow.addView(dotState);

        tvState = new TextView(this);
        tvState.setText("连接中");
        tvState.setTextColor(C_SUB);
        tvState.setTextSize(12);
        topRow.addView(tvState);

        head.addView(topRow);

        TextView who = new TextView(this);
        who.setText("妈妈");
        who.setTextColor(C_TEXT);
        who.setTextSize(30);
        who.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams whoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        whoLp.topMargin = dp(12);
        head.addView(who, whoLp);

        TextView whoSub = new TextView(this);
        whoSub.setText("可疑情况会立刻推到这里");
        whoSub.setTextColor(C_SUB);
        whoSub.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        head.addView(whoSub, subLp);

        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 长按顶栏进设置（老师问「服务器怎么配」的时候现场就能改）
        head.setLongClickable(true);
        head.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showSettings(); return true; }
        });

        // ---------- 概览卡 ----------
        LinearLayout overview = new LinearLayout(this);
        overview.setOrientation(LinearLayout.VERTICAL);
        overview.setBackground(round(C_CARD, 18));
        overview.setPadding(dp(20), dp(18), dp(20), dp(18));

        tvOverview = new TextView(this);
        tvOverview.setText("连接中…");
        tvOverview.setTextColor(C_TEXT);
        tvOverview.setTextSize(20);
        tvOverview.setTypeface(Typeface.DEFAULT_BOLD);
        overview.addView(tvOverview);

        tvOverviewSub = new TextView(this);
        tvOverviewSub.setText("正在从云端读取");
        tvOverviewSub.setTextColor(C_SUB);
        tvOverviewSub.setTextSize(13);
        LinearLayout.LayoutParams ovSub = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ovSub.topMargin = dp(6);
        overview.addView(tvOverviewSub, ovSub);

        LinearLayout.LayoutParams ovLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ovLp.leftMargin = dp(20);
        ovLp.rightMargin = dp(20);
        root.addView(overview, ovLp);

        // ---------- 列表 ----------
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(20), dp(18), dp(20), dp(28));
        sv.addView(listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, svLp);

        return root;
    }

    // ==========================================================================
    //  数据回调
    // ==========================================================================

    @Override
    public void onList(final List<Alert> list) {
        // Hub 的回调是从 SSE 后台线程发出来的，直接动 View 会抛
        // CalledFromWrongThreadException（第一次跑就是这么挂的）。一律切回主线程。
        runOnUiThread(new Runnable() {
            @Override public void run() { renderList(list); }
        });
    }

    @Override
    public void onNew(Alert a) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // 新提醒到的时候轻闪一下列表，让人能注意到顶上有变化
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

    // ==========================================================================
    //  渲染
    // ==========================================================================

    private void renderState(String state) {
        if (tvState == null) return;
        if ("live".equals(state)) {
            dotState.setBackground(round(C_OK, 4));
            tvState.setText("实时连接");
            tvState.setTextColor(C_OK);
        } else if ("connecting".equals(state)) {
            dotState.setBackground(round(C_WARN, 4));
            tvState.setText("连接中");
            tvState.setTextColor(C_WARN);
        } else {
            dotState.setBackground(round(C_BLOCK, 4));
            tvState.setText("已断开，重连中");
            tvState.setTextColor(C_BLOCK);
            // 断线时点一下顶栏可以立刻重试，不用等 3 秒
            tvState.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Hub.get().restart(); }
            });
        }
    }

    private void renderList(List<Alert> list) {
        if (listBox == null) return;
        listBox.removeAllViews();

        // ---------- 概览 ----------
        int today = 0;
        int worst = 0;
        Alert latest = null;
        String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        for (Alert a : list) {
            if (a.time != null && a.time.length() >= 5) {
                // 只统计今天：用本地时间判断不了跨天，这里退一步，
                // 只统计「收到的最近 60 条」，文案说「最近」不说「今天」，不撒谎
                today++;
                if (latest == null) latest = a;
                if (a.rank() > worst) worst = a.rank();
            }
        }
        if (list.isEmpty()) {
            tvOverview.setText("一切正常");
            tvOverview.setTextColor(C_OK);
            tvOverviewSub.setText("这几天没有发现可疑情况");
        } else {
            tvOverview.setText("最近 " + today + " 条提醒");
            tvOverview.setTextColor(worst >= 3 ? C_HIGH : C_TEXT);
            if (latest != null) {
                tvOverviewSub.setText("最近一条 " + latest.time + " · " + latest.channelWord());
            } else {
                tvOverviewSub.setText(" ");
            }
        }

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有收到提醒。\n妈妈手机上发现可疑短信、可疑来电，或者正在给骗子转账时，\n消息会立刻出现在这里。");
            empty.setTextColor(C_SUB);
            empty.setTextSize(13);
            empty.setLineSpacing(dp(6), 1f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(50), dp(10), dp(10));
            listBox.addView(empty);
            return;
        }

        TextView section = new TextView(this);
        section.setText("最近提醒");
        section.setTextColor(C_SUB);
        section.setTextSize(12);
        section.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp.bottomMargin = dp(10);
        listBox.addView(section, secLp);

        for (Alert a : list) {
            listBox.addView(card(a), cardLp());
        }
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        return lp;
    }

    /** 一条提醒卡片：左侧色条 + 标题 / 时间 / 正文 / 标签 */
    private View card(Alert a) {
        int c = colorOf(a.level);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(round(C_CARD, 16));

        View bar = new View(this);
        bar.setBackground(round(c, 3));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(4),
                ViewGroup.LayoutParams.MATCH_PARENT);
        linearTop(barLp, dp(12));
        card.addView(bar, barLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 第一行：标题 + 时间
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText(a.title.length() > 0 ? a.title : "可疑情况");
        title.setTextColor(C_TEXT);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        row1.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(this);
        time.setText(a.time);
        time.setTextColor(C_SUB);
        time.setTextSize(12);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.leftMargin = dp(8);
        row1.addView(time, tLp);
        body.addView(row1);

        // 第二行：正文
        if (a.body != null && a.body.length() > 0) {
            TextView text = new TextView(this);
            text.setText(a.body);
            text.setTextColor(0xFFB7C7DE);
            text.setTextSize(13);
            text.setLineSpacing(dp(3), 1f);
            text.setMaxLines(4);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(7);
            body.addView(text, lp);
        }

        // 第三行：AI 人话摘要（有才显示）
        if (a.summary != null && a.summary.length() > 0) {
            TextView sm = new TextView(this);
            sm.setText(a.summary);
            sm.setTextColor(0xFF8FD2B4);
            sm.setTextSize(12);
            sm.setLineSpacing(dp(3), 1f);
            sm.setPadding(dp(10), dp(8), dp(10), dp(8));
            sm.setBackground(round(0xFF12291F, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(9);
            body.addView(sm, lp);
        }

        // 第四行：标签
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsLp.topMargin = dp(10);

        chips.addView(chip(a.channelWord(), 0xFF22314B, 0xFFA8BDD8));
        chips.addView(chip(a.levelWord(), withAlpha(c), c));
        body.addView(chips, chipsLp);

        return card;
    }

    private View chip(String text, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(fg);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        t.setBackground(round(bg, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private int colorOf(String level) {
        if ("block".equals(level)) return C_BLOCK;
        if ("high".equals(level)) return C_HIGH;
        if ("warn".equals(level)) return C_WARN;
        return C_WATCH;
    }

    private int withAlpha(int c) {
        return (c & 0x00FFFFFF) | 0x33000000;
    }

    // ==========================================================================
    //  设置（长按顶栏）
    // ==========================================================================

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView tip = new TextView(this);
        tip.setText("服务器地址（形如 http://1.2.3.4:8080）");
        tip.setTextSize(12);
        tip.setTextColor(0xFF667788);
        box.addView(tip);

        final EditText edServer = new EditText(this);
        edServer.setText(Conf.get().server());
        edServer.setTextSize(14);
        edServer.setSingleLine(true);
        box.addView(edServer);

        TextView tip2 = new TextView(this);
        tip2.setText("老人编号（默认 demo）");
        tip2.setTextSize(12);
        tip2.setTextColor(0xFF667788);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2.topMargin = dp(12);
        box.addView(tip2, t2);

        final EditText edElder = new EditText(this);
        edElder.setText(Conf.get().elderId());
        edElder.setTextSize(14);
        edElder.setSingleLine(true);
        box.addView(edElder);

        final CheckBox cbSound = new CheckBox(this);
        cbSound.setText("收到提醒时响铃 + 振动");
        cbSound.setChecked(Conf.get().sound());
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cblp.topMargin = dp(14);
        box.addView(cbSound, cblp);

        TextView note = new TextView(this);
        note.setText("改完会自动重连。当前连接：" + Hub.get().state());
        note.setTextSize(11);
        note.setTextColor(0xFF667788);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        box.addView(note, nlp);

        new AlertDialog.Builder(this)
                .setTitle("连接设置")
                .setView(box)
                .setPositiveButton("保存并重连", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Conf.get().server(edServer.getText().toString());
                        Conf.get().elderId(edElder.getText().toString());
                        Conf.get().sound(cbSound.isChecked());
                        Hub.get().restart();
                        Toast.makeText(MainActivity.this, "已保存，正在重新连接", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==========================================================================
    //  权限 / 尺寸工具
    // ==========================================================================

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp((int) radiusDp));
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void linearTop(ViewGroup.MarginLayoutParams lp, int top) {
        lp.topMargin = top;
    }
}
