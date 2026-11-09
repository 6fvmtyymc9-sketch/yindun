package com.yindun.guard;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NotiGuard —— 通知监听。两只眼睛：一只在盖屏时清场，一只盯微信
 * ============================================================
 * 【第一只眼睛：盖屏期间清通知】
 *
 * 盖屏上写着"验证码已被遮住，念不出来"。
 * 可实测时，验证码短信进来的那一秒，屏幕顶部会弹出一条通知：
 *
 *     【工商银行】您的验证码是 772911……
 *
 * 号码原原本本写在通知栏里，正对着老人的眼睛。
 * 骗子只要说一句"念一下屏幕上那串数字"，这块红屏就白盖了。
 * 所以我们拿到"通知使用权"后，盖屏期间来一条清一条。
 *
 * 【第二只眼睛：微信消息】
 *
 * 老人被骗的主战场是微信，不是短信。但安卓不给任何 App 读微信聊天内容的权限，
 * 除非用无障碍去扒屏幕（费电、误报高、隐私上说不清）。
 *
 * 还好有个正规口子：微信来消息时会在通知栏发一条通知，标题是发信人、
 * 正文是消息内容。拿到"通知使用权"就能读到这两个字段——这是系统公开给用户的
 * 信息，不是偷偷翻聊天记录。
 *
 * 读到之后不做任何判断，全部交给 Judge 用同一份词库打分：
 *   诈骗话术 → 弹浮条 + 推子女端
 *   正常聊天 → 什么都不做
 *
 * 【为什么不用无障碍读屏】
 *   读屏要盯着整个屏幕的文字，微信里聊什么都进我们的眼。
 *   通知只包含"刚到达的那一条"，范围最小，答辩时也讲得清楚。
 *   代价是：老人正开着微信聊天界面时，微信不会再发通知，这一秒是盲区。
 *   这个盲区我们认，不拿隐私去换。
 *
 * 【权限说明】
 * 需要用户在「设置 → 通知使用权」里勾选银盾（不能静默获取）。
 * 没勾不影响其它功能，只是盖屏时管不住通知栏、也看不见微信消息。
 * ============================================================
 */
public class NotiGuard extends NotificationListenerService {

    private static final String TAG = "YinDun";

    /** 只认这一个包名。别的 App 的通知一个字都不碰 */
    private static final String PKG_WECHAT = "com.tencent.mm";

    /** 到达 warn（橙）才打扰老人。watch 只落库，不弹 */
    private static final int MIN_RANK = 2;

    /** 同一条消息 10 分钟内不重复弹。微信会对同一条通知反复更新（"正在输入"等） */
    private static final long DEDUP_MS = 10 * 60 * 1000L;

    /**
     * 最近判过的消息指纹 → 判定时间。
     *
     * 【为什么用指纹不用通知 key】
     * 微信同一个会话始终用同一个通知 key，内容却一直在变。
     * 只按 key 去重，第二条诈骗消息就永远进不来；只按内容去重，
     * 同一条消息被系统重复回调又会弹两次。所以 key + 正文一起算指纹。
     */
    private final Map<String, Long> seen = new LinkedHashMap<String, Long>(32, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 40;
        }
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;

        // ---------- 第一只眼睛：盖屏期间清场 ----------
        if (Shield.showing()) {
            try {
                cancelNotification(sbn.getKey());
                Log.i(TAG, "盖屏期间清掉一条通知：" + pkg);
            } catch (Exception e) {
                Log.w(TAG, "清通知失败：" + e.getMessage());
            }
            return;      // 盖屏时不再判微信，红屏已经说了更要紧的话
        }

        // ---------- 第二只眼睛：微信消息判定 ----------
        if (PKG_WECHAT.equals(pkg)) {
            try {
                judgeWechat(sbn);
            } catch (Exception e) {
                Log.e(TAG, "分析微信通知出错", e);
            }
        }
    }

    /**
     * 读一条微信通知，判它是不是诈骗。
     *
     * 拿到的就两个字段：
     *   title = 谁发的（微信备注名 / 群名）
     *   text  = 那条消息的正文
     *
     * 【为什么判定要扔到后台线程】
     * 2026-11-09 实测踩的坑：微信连发三条消息，这里同步判定 + 弹浮条 + 推网页，
     * 五秒内把主线程塞满，系统直接判银盾 ANR（弹"银盾 isn't responding"）。
     *
     * 这个回调跑在**进程主线程**上 —— 和 MainActivity 是同一个主线程。
     * 所以只要这里多花几百毫秒，用户眼前那个界面就跟着卡。
     * 判定本身是纯计算、不碰 UI，没有理由占着主线程。
     */
    private void judgeWechat(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        Bundle ex = n.extras;
        CharSequence csTitle = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence csText = ex.getCharSequence(Notification.EXTRA_TEXT);

        final String from = csTitle == null ? "" : csTitle.toString().trim();
        final String body = csText == null ? "" : csText.toString().trim();
        if (body.length() < 4) return;          // 太短的（"在吗"）不值得判

        // 群消息的通知正文常带"某某: 内容"，标题是群名，这里不拆，整段交给词库
        //
        // 【指纹为什么要带 postTime】
        // 2026-11-09 实测：只按 key + 正文算指纹，演示脚本重跑第二遍时，
        // 内容一模一样、通知 id 也一模一样，全被当成"刚判过"拦掉了，
        // 浮条再也不弹 —— 现场重演一次就废。
        // 通知的发出时间是系统打的，重发一次必然不同，带上它就等于
        // "同一条消息只判一次，重新发的照样算新的"。
        final String fp = sbn.getKey() + "#" + body.hashCode() + "#" + sbn.getPostTime();
        if (!fresh(fp)) return;

        final Context app = getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 微信里发消息的不是通讯录里的号码，也不是服务号 —— 一律按陌生人算。
                    // 注意这一步本身会 +15，但还不够 warn 的门槛，正常聊天不会被误报。
                    Judge.Result r = Judge.get(app).judgeSms(body, NumberInfo.STRANGER, false);

                    Log.i(TAG, "微信消息 from=" + from + " 分数=" + r.score
                            + " 阶段=" + r.stage + " 级别=" + r.level + " 原因=" + r.why);

                    if (rank(r.level) < MIN_RANK) return;   // 正常聊天，不打扰

                    // ---------- 弹给老人看 ----------
                    // 【为什么静默】老人可能正拿着手机，突然一声音效会把她从
                    // "听骗子说话"里拽出来，反而暴露了手机在替她判断。
                    // 视觉提示足够，她低头就看见。
                    // 【为什么从下方弹】微信通知本身也是顶部横幅，浮条贴顶会被压住。
                    // 【为什么 post 回主线程】悬浮窗只能在主线程操作。
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            TipBar.showBottom(NotiGuard.this,
                                    r.level, "疑似诈骗信息", "请谨慎点击短信链接");
                        }
                    });

                    // ---------- 推给子女端 ----------
                    EventBus.pushAlert(r.level, r.why, r.stage, r.score);
                    Notifier.alertChild(app, r.level,
                            "妈妈微信收到可疑消息",
                            "来自「" + from + "」：" + brief(body));

                    Log.i(TAG, "已向老人弹浮条、向子女推送（微信可疑消息）");

                } catch (Exception e) {
                    Log.e(TAG, "微信消息判定出错", e);
                }
            }
        }, "yindun-wx-judge").start();
    }

    /** 主线程用的 handler（悬浮窗只能在主线程操作） */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 这条消息是不是新的（同一条 10 分钟内只判一次） */
    private synchronized boolean fresh(String fp) {
        long now = System.currentTimeMillis();
        Long last = seen.get(fp);
        if (last != null && now - last < DEDUP_MS) return false;
        seen.put(fp, now);
        return true;
    }

    private int rank(String level) {
        if ("block".equals(level)) return 4;
        if ("high".equals(level)) return 3;
        if ("warn".equals(level)) return 2;
        if ("watch".equals(level)) return 1;
        return 0;
    }

    private String brief(String s) {
        if (s == null) return "";
        s = s.replace("\n", " ").trim();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    /** 服务连上系统时打个日志，方便现场确认权限到底有没有生效 */
    @Override
    public void onListenerConnected() {
        Log.i(TAG, "通知使用权已生效（盖屏清通知 + 微信消息判定）");
    }
}
