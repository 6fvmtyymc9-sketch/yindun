package com.tencent.mm;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 微信壳 —— 只干两件事：显示一条聊天，和为每条消息发一条系统通知
 * ============================================================
 * 【它这里没有任何判定】
 * 整个 App 里搜不到"诈骗""可疑""评分"这些词。它跟真微信做的一样：
 * 收到消息 → 画个气泡 → 发通知。判不判得出来，是银盾那边的事。
 *
 * 这一点很重要：如果壳里自己判断"这条要报警"，那演示就成了演戏。
 * 现在这条链上，银盾看到的只有两样东西——包名和通知正文，
 * 它必须靠自己那份 rules.json 判出来，判不出来就一声不响。
 *
 * 【为什么有 4 条，其中还有一条正常的】
 * 为了证明判定是真的：
 *   第 1 条是正常聊天 → 银盾不应该有任何反应
 *   第 4 条是钓鱼链接 → 银盾应该弹浮条
 * 如果见消息就报，那第 1 条也会报，一验就知道是假的。
 * ============================================================
 */
public class MainActivity extends Activity {

    private static final String TAG = "FakeWechat";
    private static final String CHANNEL_ID = "wx_chat";
    private static final int NOTI_ID = 2001;

    /** 发消息的人。真微信里这就是对方的备注名 */
    private static final String PEER = "张主任";

    /**
     * 消息脚本。逐条"收到"，节奏由人控制，方便演示时停下来讲话。
     *
     * 第 1 条故意是正常话（拉家常），用来验证银盾不是见消息就报。
     * 第 2~4 条是一条完整的社保诈骗话术：亮身份 → 吓唬 → 隔离 → 钓鱼链接。
     */
    private static final String[] SCRIPT = {
            "小李啊，我是社保局这边负责您片区的，工号 0371",
            "系统显示您的医保卡被他人冒用，涉及一笔 1.8 万的违规报销",
            "这个事您先不要告诉家里人，说出去会影响我们调查",
            "您现在点这个链接完成认证 t.cn/Yb9K2，逾期账户会被直接注销"
    };

    private int idx = 0;
    private LinearLayout chatCol;
    private ScrollView scroller;
    private TextView demoBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Android 13 起发通知要用户授权。不给的话银盾也读不到，所以先要。
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        setContentView(buildUi());

        // 打开 1.2 秒后自动来第一条 —— 让演示的人不用点也能看到通知弹出来
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                receiveNext();
            }
        }, 1200);
    }

    // ==========================================================
    //  界面
    // ==========================================================
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFEDEDED);

        // ---------- 顶栏 ----------
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFEDEDED);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setTextColor(0xFF111111);
        back.setGravity(Gravity.CENTER);
        bar.addView(back, new LinearLayout.LayoutParams(dp(28), dp(40)));

        TextView title = new TextView(this);
        title.setText(PEER);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(0xFF111111);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, tp);

        TextView more = new TextView(this);
        more.setText("⋯");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        more.setTextColor(0xFF111111);
        more.setGravity(Gravity.CENTER);
        bar.addView(more, new LinearLayout.LayoutParams(dp(28), dp(40)));

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 顶栏下面一条细分割线
        View line = new View(this);
        line.setBackgroundColor(0xFFDADADA);
        root.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // ---------- 聊天区 ----------
        scroller = new ScrollView(this);
        scroller.setBackgroundColor(0xFFEDEDED);
        scroller.setFillViewport(true);

        chatCol = new LinearLayout(this);
        chatCol.setOrientation(LinearLayout.VERTICAL);
        chatCol.setPadding(dp(12), dp(14), dp(12), dp(14));
        scroller.addView(chatCol, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------- 底部输入栏（只做样子，不能打字） ----------
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setBackgroundColor(0xFFF7F7F7);
        inputBar.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView plus = new TextView(this);
        plus.setText("＋");
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        plus.setTextColor(0xFF666666);
        plus.setGravity(Gravity.CENTER);
        inputBar.addView(plus, new LinearLayout.LayoutParams(dp(28), dp(40)));

        TextView fakeInput = new TextView(this);
        fakeInput.setText("发送消息");
        fakeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        fakeInput.setTextColor(0xFF9A9A9A);
        fakeInput.setGravity(Gravity.CENTER_VERTICAL);
        fakeInput.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(0xFFFFFFFF);
        ib.setCornerRadius(dp(6));
        fakeInput.setBackground(ib);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        fp.rightMargin = dp(8);
        inputBar.addView(fakeInput, fp);

        TextView emoji = new TextView(this);
        emoji.setText("☺");
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        emoji.setTextColor(0xFF666666);
        emoji.setGravity(Gravity.CENTER);
        inputBar.addView(emoji, new LinearLayout.LayoutParams(dp(28), dp(40)));

        root.addView(inputBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------- 演示控制条 ----------
        // 【为什么单独画一条、还写明"演示"】
        // 不把它伪装成微信的输入框。答辩时评委一眼能看出这是演示控件，
        // 而不是以为我们在偷偷放按钮。
        demoBtn = new TextView(this);
        demoBtn.setText("演示：模拟收到下一条消息");
        demoBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        demoBtn.setTextColor(0xFF1B8A4B);
        demoBtn.setGravity(Gravity.CENTER);
        demoBtn.setPadding(0, dp(10), 0, dp(10));
        demoBtn.setBackgroundColor(0xFFE8F6EC);
        demoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                receiveNext();
            }
        });
        root.addView(demoBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /** 加一个绿色气泡（对方发来的） */
    private void addBubble(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bubble.setTextColor(0xFF111111);
        bubble.setLineSpacing(dp(3), 1f);
        bubble.setPadding(dp(13), dp(10), dp(13), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF95EC69);          // 微信的"对方"绿
        bg.setCornerRadius(dp(6));
        bubble.setBackground(bg);

        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.72f);
        bubble.setMaxWidth(maxW);

        row.addView(bubble, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        chatCol.addView(row, lp);

        scroller.post(new Runnable() {
            @Override
            public void run() {
                scroller.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ==========================================================
    //  收到下一条
    // ==========================================================
    private void receiveNext() {
        if (idx >= SCRIPT.length) {
            demoBtn.setText("脚本已播完（共 " + SCRIPT.length + " 条）");
            demoBtn.setEnabled(false);
            demoBtn.setTextColor(0xFF888888);
            return;
        }
        String text = SCRIPT[idx++];
        addBubble(text);
        postNotification(text);
        Log.i(TAG, "收到第 " + idx + " 条：" + text);
        if (idx >= SCRIPT.length) {
            demoBtn.setText("脚本已播完（共 " + SCRIPT.length + " 条）");
            demoBtn.setEnabled(false);
            demoBtn.setTextColor(0xFF888888);
        }
    }

    /**
     * 发一条系统通知。
     *
     * 【为什么必须真的发通知】
     * 银盾就是靠这条通知读到消息内容的。如果这里只是画个气泡不发通知，
     * 银盾什么都看不见，演示就成了空转。
     *
     * 【为什么静音】
     * 演示时反复响会打断讲解；而且真实的微信消息提示音也已经关掉过（很多人这么用）。
     * 判定和报警不靠声音，靠屏幕上的浮条。
     */
    private void postNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "微信消息", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }

        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(PEER)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setDefaults(0);          // 不要声音、不要振动、不要呼吸灯

        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_LOW);
        }

        nm.notify(NOTI_ID + idx, b.build());
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
