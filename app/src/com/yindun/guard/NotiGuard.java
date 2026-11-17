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

    /**
     * 系统短信 App 的包名。
     *
     * 【为什么是一张表而不是一个名字】
     * 短信 App 不像微信那样全平台统一：AOSP、华为、小米、OPPO、vivo 用的是
     * com.android.mms，Google 系是 com.google.android.apps.messaging，三星、魅族各家又不同。
     * 这里只做一件事 —— 认出"这是短信"，然后拿它去对照表里查有没有被判过诈骗。
     * 查不到就什么都不做，所以多列几个包名没有副作用。
     */
    private static final String[] PKG_SMS = {
            "com.android.mms",                          // AOSP / 华为 / 小米 / OPPO / vivo
            "com.android.messaging",                    // AOSP 旧版
            "com.google.android.apps.messaging",        // Google 信息
            "com.samsung.android.messaging",            // 三星
            "com.oneplus.mms",                          // 一加
            "com.meizu.mms",                            // 魅族
            "com.huawei.mms"                            // 华为部分版本
    };

    /** 到达 warn（橙）才打扰老人。watch 只落库，不弹 */
    private static final int MIN_RANK = 2;

    /**
     * 兜底的时间桶粒度：只在这条通知拿不到 postTime 时才用。
     *
     * 【2026-11-17 改：主判据换成通知自己的 postTime】
     * 原来指纹里放的就是这个 5 分钟桶，实测出了真问题：
     * 同一段话（骗子重复催"转3000到这个卡"，或者用户自己复测）
     * 在 5 分钟内再发一次，指纹一模一样，直接被当成"刚判过"跳过 ——
     * 屏幕上什么都不弹，子女端也收不到，老人以为银盾不认这条了。
     * "是不是同一条消息"的权威答案是系统给的通知时间，不该由我们拿时钟猜。
     * 换成 postTime 之后：系统重复回调同一个通知（postTime 不变）照样只判一次，
     * 而微信真的又收到一条（哪怕一个字都没变），postTime 会前进，就会重新判。
     */
    private static final long DEDUP_BUCKET_MS = 5 * 60 * 1000L;

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
            return;
        }

        // ---------- 第三只眼睛：把系统短信通知换成银盾自己的 ----------
        if (isSmsApp(pkg)) {
            try {
                judgeSmsNoti(sbn);
            } catch (Exception e) {
                Log.e(TAG, "处理短信通知出错", e);
            }
        }
    }

    /** 这个包名是不是系统短信 App */
    private static boolean isSmsApp(String pkg) {
        for (String p : PKG_SMS) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    /**
     * 一条短信通知到了 —— 被判过诈骗的就换掉它。
     *
     * 【为什么这里不做判定】
     * 判定已经在 SmsReceiver 里做完了（短信广播比通知先到），这里再做一次就是
     * 重复判、重复推子女、重复弹浮条。所以这里只查对照表：查到说明这条刚被判过
     * 是诈骗，就把系统那条通知换成银盾的话；查不到就是一条正常短信，一个字都不碰。
     *
     * 【为什么非换不可】
     * 老人下拉通知栏，看到的是一条和快递、工行毫无区别的短信通知，点开就是骗子的原话。
     * 我们判出来了、也告诉女儿了，唯独最该知道的那个人什么都不知道。
     */
    private void judgeSmsNoti(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        Bundle ex = n.extras;
        String body = notificationText(ex);
        Blocked.Item hit = Blocked.take(body);
        if (hit == null) return;               // 正常短信，不动

        CharSequence cs = ex.getCharSequence(Notification.EXTRA_TITLE);
        String from = cs == null ? "" : cs.toString().trim();
        if (from.length() == 0) from = hit.from;

        // 先撤掉系统那条。
        //
        // 【2026-11-16 实测】只撤"具体那条"不够：Google Messages 把同一会话的
        // 通知拆成「组摘要 + 具体消息」两条，撤掉子通知后摘要还留着那条内容，
        // 系统随即把子通知重建回来 —— dump 出来的通知列表里两条都在。
        // 所以同包的组摘要也一起撤。
        cancelSmsNoti(sbn.getKey(), sbn.getPackageName());

        Notifier.notifyElderScam(getApplicationContext(), from);
        Log.w(TAG, "系统短信通知已换成银盾警告，来自「" + from + "」");
    }

    /**
     * 撤掉系统短信 App 的那条通知，连它所在的通知组一起。
     *
     * 只撤命中的那一条会白撤：短信 App 的通知是「组摘要 + 成员」结构，
     * 摘要里仍然带着正文，系统会把成员重建回来。
     * 这里只对同一个包动手，别的 App 的通知一个字都不碰。
     */
    private void cancelSmsNoti(String key, String pkg) {
        int gone = 0;
        try {
            cancelNotification(key);
            gone++;
        } catch (Exception e) {
            Log.w(TAG, "撤短信通知失败：" + e.getMessage());
        }

        try {
            for (StatusBarNotification s : getActiveNotifications()) {
                if (s == null || !pkg.equals(s.getPackageName())) continue;
                if (s.getKey().equals(key)) continue;
                Notification n = s.getNotification();
                if (n == null) continue;
                // 组摘要（FLAG_GROUP_SUMMARY）才撤；同一 App 的其他独立通知不动
                if ((n.flags & Notification.FLAG_GROUP_SUMMARY) == 0) continue;
                cancelNotification(s.getKey());
                gone++;
            }
        } catch (Exception e) {
            Log.w(TAG, "撤通知组失败：" + e.getMessage());
        }

        Log.i(TAG, "撤掉系统短信通知 " + gone + " 条（" + pkg + "）");
    }

    /**
     * 取通知正文。
     *
     * 大多数短信 App 把正文放在 EXTRA_TEXT；也有 ROM 用 MessagingStyle，
     * 正文在 EXTRA_MESSAGES 这个数组里（每条是一个 Bundle）。两条路都试一遍。
     * 常量直接写字符串值，避免为了一个字段去卡 API 版本。
     */
    private static String notificationText(Bundle ex) {
        CharSequence t = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (t != null && t.length() > 0) return t.toString();

        Object o = ex.get("android.messages");
        if (o instanceof Object[]) {
            Object[] arr = (Object[]) o;
            for (int i = arr.length - 1; i >= 0; i--) {
                if (!(arr[i] instanceof Bundle)) continue;
                CharSequence m = ((Bundle) arr[i]).getCharSequence("text");
                if (m != null && m.length() > 0) return m.toString();
            }
        }
        return "";
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

        // 【2026-11-16 加】同一个会话连来好几条时，微信的通知正文会变成
        // "2 条新消息"这类汇总文案，真正的消息内容不在里面。
        // 拿这句话去判词库，得出的结论毫无意义（还可能是高分），
        // 所以直接跳过 —— 等它展开成具体内容时，下一条通知会带正文。
        if (body.matches("^\\d+\\s*条新消息.*$") || body.matches("^\\[\\d+条\\].*$")) {
            Log.i(TAG, "微信通知是多条合并（" + body + "），跳过判定");
            return;
        }

        // 群消息的通知正文常带"某某: 内容"，标题是群名，这里不拆，整段交给词库
        //
        // 【指纹为什么带 postTime】（2026-11-17 改）
        // 原来带的是"5 分钟时间桶"，于是同一段话在 5 分钟内再发一次就完全静默：
        // 骗子反复催同一句话、或者现场复测同一段文案，第二次起什么都不弹。
        // 判"这是不是又一条新消息"，权威答案是系统记的通知时间，不是我们的时钟：
        //   - 系统把同一个通知重复回调给我们（列表重建、设备恢复）→ postTime 不变 → 跳过
        //   - 微信真的又收到一条，哪怕一个字都没改 → 通知重新 post，postTime 前进 → 重新判
        // postTime 拿不到（个别 ROM 给 0）才退回时间桶，至少保证不会疯狂重复弹。
        long stamp = sbn.getPostTime();
        if (stamp <= 0) stamp = System.currentTimeMillis() / DEDUP_BUCKET_MS;
        final String fp = sbn.getKey() + "#" + body.hashCode() + "#" + stamp;
        if (!fresh(fp)) return;

        final Context app = getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // ---------- 先过一道最硬的：通话中被索要验证码 ----------
                    // 【2026-11-16 新增，对应短信侧 SmsReceiver 第 5 步】
                    // 短信那条链早就是完整闭环，微信这条一直是空的。而现实里骗子
                    // 更常用微信要验证码：能发语音、能发假截图、冒充亲人比短信可信得多。
                    //
                    // 触发条件不是"这条消息可疑"，而是"她此刻正和陌生号码通话"。
                    // 银行客服、快递、家人，都不会在电话里让老人把验证码念出来——
                    // 那一秒说这句话只可能是骗子。所以这条走 block，不吃 MIN_RANK 那道 warn 门槛。
                    if (looksLikeCodeRequest(body) && strangerOnCall(app)) {
                        Log.w(TAG, "★ 通话中被微信索要验证码 —— 阻断级");
                        Guard.get(app).onCodeInCall();   // 置标志位并进判定链
                        EventBus.pushAlert("block", "通话中被微信索要验证码", "S4", 100);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                TipBar.showBottom(NotiGuard.this, "block",
                                        "有人在问您要验证码",
                                        "不管是微信还是电话，验证码谁都不能给");
                            }
                        });
                        Notifier.alertChild(app, Notifier.K_CODE, "block",
                                "妈妈正在通话中被人索要验证码",
                                "微信来自「" + brief(from) + "」：" + brief(body));
                        // 这条是实打实的阻断级：通知留在栏里，老人一点就回
                        // 到那个让她念数字的对话
                        clearIfBlock("block", sbn);
                        return;
                    }

                    // 微信里发消息的不是通讯录里的号码，也不是服务号 —— 一律按陌生人算。
                    // 注意这一步本身会 +15，但还不够 warn 的门槛，正常聊天不会被误报。
                    //
                    // 【2026-11-16 加：群消息不当"陌生人私聊"判】
                    // 微信群聊的通知正文是「张三: 内容」，标题是群名。原来一律按
                    // STRANGER 起手 +15，家里人在群里说一句"我收到个验证码，不知道是谁的"，
                    // 加上"验证码"本身的权重就够到 45 线 → 弹浮条 + 推子女。
                    // 老人的家庭群是高频场景，撞一次她就觉得银盾乱弹。
                    //
                    // 处理：群消息只在判到 S4（要钱 / 要码）时才打扰。
                    // 真正的骗局不会在群里闲聊两句就收手，它一定要东西；
                    // 而"谁家孩子考了多少分"这类闲聊永远走不到 S4。
                    final boolean group = looksGroupMessage(body);
                    Judge.Result r = Judge.get(app).judgeSms(body, NumberInfo.STRANGER, false);
                    Stats.wechat(app);

                    Log.i(TAG, "微信消息 from=" + from + (group ? "（群聊）" : "")
                            + " 分数=" + r.score
                            + " 阶段=" + r.stage + " 级别=" + r.level + " 原因=" + r.why);

                    if (rank(r.level) < MIN_RANK) return;   // 正常聊天，不打扰
                    if (group && !"S4".equals(r.stage)) {
                        // 群里的提醒仍留在内部记录里，只是不弹、不推
                        Log.i(TAG, "群消息没走到 S4，不打扰老人和子女");
                        return;
                    }

                    // ---------- 弹给老人看 ----------
                    // 【为什么静默】老人可能正拿着手机，突然一声音效会把她从
                    // "听骗子说话"里拽出来，反而暴露了手机在替她判断。
                    // 视觉提示足够，她低头就看见。
                    // 【为什么从下方弹】微信通知本身也是顶部横幅，浮条贴顶会被压住。
                    // 【为什么 post 回主线程】悬浮窗只能在主线程操作。
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            // 【2026-11-16 改：说清楚是谁发的】
                            // 原来只写"疑似诈骗消息"。老人微信里同时开着好几个会话，
                            // 她不知道指的哪一条，转头就去点了那条通知。
                            // from 在上一步就已经拿到了，只是没往浮条上传。
                            String who = from.length() > 12 ? from.substring(0, 12) + "…" : from;
                            TipBar.showBottom(NotiGuard.this, r.level,
                                    who.length() == 0 ? "微信消息可疑" : "「" + who + "」的消息可疑",
                                    "请勿点击消息中的链接或转账");
                        }
                    });

                    // ---------- 推给子女端 ----------
                    EventBus.pushAlert(r.level, r.why, r.stage, r.score);
                    Notifier.alertChild(app, Notifier.K_WECHAT, r.level,
                            "妈妈微信收到可疑消息",
                            "来自「" + from + "」：" + brief(body));

                    // ---------- 阻断级的，把这条通知从通知栏清掉 ----------
                    // 【2026-11-16 加，用户拍板：只清 block】
                    // 原来浮条说"别点"，通知栏里却还挂着那个可以点的入口 ——
                    // 提醒和入口并存，老人手指一滑就进去了。
                    // 为什么 warn 级不清：warn 可能是误报，删掉她反而收不到家人的正常消息。
                    // 只有判到 S4（要钱/要码）才值得动这一下手。
                    clearIfBlock(r.level, sbn);

                    Log.i(TAG, "已向老人弹浮条、向子女推送（微信可疑消息）");

                } catch (Exception e) {
                    Log.e(TAG, "微信消息判定出错", e);
                }
            }
        }, "yindun-wx-judge").start();
    }

    /**
     * 判到阻断级时，把这条微信通知从通知栏撤掉。
     *
     * 只清 block：warn 可能是误报，替老人删掉一条家人消息的代价太大。
     * 清不掉也不影响其它流程（可能是系统限制），只记一条日志。
     */
    private void clearIfBlock(String level, StatusBarNotification sbn) {
        if (!"block".equals(level) || sbn == null) return;
        try {
            cancelNotification(sbn.getKey());
            Log.i(TAG, "阻断级：已从通知栏清掉这条微信消息");
        } catch (Exception e) {
            Log.w(TAG, "清微信通知失败：" + e.getMessage());
        }
    }

    /**
     * 这句话像不像在"向老人索要验证码"。
     *
     * 【为什么不能只看有没有"验证码"三个字】
     * 「我把验证码发你了」是给，正常；「你把验证码告诉我」是要，诈骗。
     * 方向反了，判断就全反。所以要同时满足：出现验证码类词 + 出现索要动词。
     *
     * 【为什么会话词里有"多少""几位"】
     * 骗子很少说"请告诉我验证码"，更常说"收到几位数字？""你念一下那个码"。
     * 这些词单独看毫无意义，但前面已经锁定了"验证码"这个词，
     * 所以组合起来的误报很低。
     */
    private static final String[] CODE_WORDS = {
            "验证码", "校验码", "动态码", "确认码", "授权码", "安全码", "短信码",
            // 【2026-11-16 补】骗子往往不说全称，只说"那几位数""发个码给我"。
            // 这几条单独出现没有意义（"几位数"还可能是在问年龄），
            // 但必须同时命中下面 ASK_WORDS 里的索要词才算数，
            // 外面还压着 strangerOnCall() 这道门，误报进不来。
            "那几位", "几位数", "那个数字", "短信里的数"
    };

    private static final String[] ASK_WORDS = {
            "发我", "发给我", "告诉我", "报给我", "报一下", "念一下", "念给我",
            "念出来", "说给我", "发过来", "发一下", "多少", "几位", "几个数字", "收到没有",
            // 【2026-11-16 补】骗子换个动作说同一件事：不"念"，让老人自己"输入"。
            // 对老人来说这一步更危险 —— 她自己把数字敲进骗子给的页面里，
            // 而我们原本的词表里，"输入"压根不算"索要"。
            "输入", "读一下", "读给我", "报个数"
    };

    private boolean looksLikeCodeRequest(String body) {
        if (body == null || body.length() == 0) return false;

        boolean hasCode = false;
        for (String w : CODE_WORDS) {
            if (body.contains(w)) { hasCode = true; break; }
        }

        // 【2026-11-16 补：裸"码"】
        // 骗子常说"把码发我""码是多少"，不说全称。但"码"这个字在微信里太常见，
        // 直接加进词表会把"收款码发我""扫个码"一起咬进来。
        // 做法：先把日常说法整段抠掉，剩下的字里还有"码"，才认它是在说验证码。
        if (!hasCode) hasCode = bareCode(body);
        if (!hasCode) return false;

        for (String w : ASK_WORDS) {
            if (body.contains(w)) return true;
        }
        return false;
    }

    /** 去掉"扫码/二维码/收款码"这类日常说法后，还剩不剩一个"码"字 */
    private static final String[] CODE_SAFE = {
            "扫码", "二维码", "收款码", "付款码", "健康码", "行程码", "号码", "密码",
            "筹码", "明码", "价码", "代码", "编码", "解码", "条码", "图标码"
    };

    private static boolean bareCode(String body) {
        String t = body;
        for (String s : CODE_SAFE) t = t.replace(s, "");
        return t.contains("码");
    }

    /**
     * 此刻是不是"正和一个陌生人通话"。
     *
     * Guard 是老人端单例，NotiGuard 拿到的是 application context，跨进程组件里
     * 万一 Guard 还没初始化，这里不能把整条微信判定带崩（历史上踩过这个坑）。
     */
    private boolean strangerOnCall(Context ctx) {
        try {
            Guard g = Guard.get(ctx);
            return g.callStartAt > 0 && NumberInfo.STRANGER.equals(g.st.callWho);
        } catch (Exception e) {
            Log.w(TAG, "取不到通话状态，按未通话处理：" + e.getMessage());
            return false;
        }
    }

    /** 主线程用的 handler（悬浮窗只能在主线程操作） */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * 这条微信通知像不像群消息。
     *
     * 依据是微信自己的通知格式：群消息正文是「发送人: 内容」，单聊正文就是内容。
     * 只在正文前 20 个字内出现冒号、且冒号前面"像个名字"（无空格、无标点）时才认，
     * 避免把"温馨提示：明天降温"这类正文误判成群消息。
     *
     * 判断错了的代价是不对称的：真群消息被当成单聊 → 多弹一次；
     * 单聊被当成群聊 → 只在 S4 才报，可能晚一步。所以宁可判得保守（宁可认出群聊）。
     */
    private boolean looksGroupMessage(String body) {
        if (body == null || body.length() < 3) return false;
        int colon = -1;
        int limit = Math.min(body.length(), 20);
        for (int i = 0; i < limit; i++) {
            char ch = body.charAt(i);
            if (ch == ':' || ch == '：') { colon = i; break; }
        }
        if (colon <= 0) return false;
        String head = body.substring(0, colon).trim();
        if (head.length() == 0 || head.length() > 16) return false;
        for (int i = 0; i < head.length(); i++) {
            char ch = head.charAt(i);
            if (ch == ' ' || ch == '，' || ch == '。' || ch == '！' || ch == '？') return false;
        }
        return true;
    }

    /** 这条通知是不是新的（指纹见 judgeWechat 里 postTime 那段说明） */
    private synchronized boolean fresh(String fp) {
        if (seen.containsKey(fp)) return false;
        seen.put(fp, System.currentTimeMillis());
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

    /**
     * 服务连上系统时打个日志，方便现场确认权限到底有没有生效。
     *
     * 【顺带把通话监听拉回来】这一段不是可有可无的。
     * 通知监听的绑定和进程的生命周期绑在一起：装新包、系统回收之后，
     * 系统会为了重新绑定本服务把进程拉起来，但**不会**顺手把 GuardService 拉起来。
     * 结果就是进程活着、短信微信都在判、通话监听却是瞎的，而且一条错误都不报。
     * 2026-11-16 实测漏掉过整整两分钟（详见 GuardService.ensureRunning 的注释）。
     */
    @Override
    public void onListenerConnected() {
        Log.i(TAG, "通知使用权已生效（盖屏清通知 + 微信消息判定）");
        GuardService.ensureRunning(this);
    }
}
