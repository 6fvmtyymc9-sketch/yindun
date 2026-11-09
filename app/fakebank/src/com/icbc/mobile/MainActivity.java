package com.icbc.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 演示用「银行首页」。
 *
 * <p>界面完全用代码搭，不依赖 layout 文件，改起来方便。
 * 目的只有一个：让投影上看起来"老人真的打开了手机银行"，
 * 不是一张空白页。
 */
public class MainActivity extends Activity {

    private static final int RED = 0xFFC7000B;      // 工行红
    private static final int RED_DARK = 0xFF9E0009;
    private static final int BG = 0xFFF5F6F8;
    private static final int TEXT = 0xFF1A1A1A;
    private static final int TEXT_SUB = 0xFF8A8F99;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(topBar());

        // ---------- 账户卡片 ----------
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{RED, RED_DARK});
        cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);
        card.setPadding(dp(22), dp(20), dp(22), dp(22));

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(dp(16), dp(16), dp(16), 0);
        root.addView(card, cp);

        card.addView(txt("我的账户  6222 **** 3856", 13, 0xCCFFFFFF, false));
        card.addView(txt("¥ 186,420.35", 30, 0xFFFFFFFF, true),
                lpTop(10));

        // ---------- 三个主功能 ----------
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(dp(16), dp(26), dp(16), 0);
        root.addView(row, rp);

        row.addView(func("转账汇款", true), weight());
        row.addView(func("收款", false), weight());
        row.addView(func("账户明细", false), weight());

        // ---------- 余额区（让它看起来有内容） ----------
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable white = new GradientDrawable();
        white.setColor(Color.WHITE);
        white.setCornerRadius(dp(14));
        list.setBackground(white);
        list.setPadding(dp(18), dp(6), dp(18), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(26), dp(16), 0);
        root.addView(list, lp);

        list.addView(item("工资入账", "+ 8,600.00", 0xFF00A05A));
        list.addView(divider());
        list.addView(item("转出 · 李**", "- 50,000.00", 0xFFFF3B30));
        list.addView(divider());
        list.addView(item("消费 · 超市", "- 128.50", TEXT));

        // ---------- 底部说明 ----------
        TextView foot = txt("演示样例 · 非真实银行应用", 12, TEXT_SUB, false);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(22);
        root.addView(foot, fp);

        setContentView(root);
    }

    // ==================== 零件 ====================

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(RED);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), 0, dp(18), 0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        TextView title = txt("中国工商银行", 18, 0xFFFFFFFF, true);
        bar.addView(title);
        return bar;
    }

    private View func(String name, boolean primary) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        TextView dot = new TextView(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(primary ? RED : 0xFFEDEFF3);
        dot.setBackground(d);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(dp(46), dp(46));
        dot.setLayoutParams(dp2);
        box.addView(dot);

        TextView label = txt(name, 13, TEXT, false);
        label.setGravity(Gravity.CENTER);
        box.addView(label, lpTop(8));
        return box;
    }

    private View item(String left, String right, int color) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(15), 0, dp(15));

        TextView l = txt(left, 15, TEXT, false);
        TextView rr = txt(right, 15, color, true);
        r.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(rr);
        return r;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFF0F1F3);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private TextView txt(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lpTop(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(dp);
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
