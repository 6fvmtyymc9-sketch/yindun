package com.yindun.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Notifier —— 三种"叫醒别人"的方式
 * ============================================================
 * 1. 通知（Notification）   —— 轻，可能被忽略
 * 2. 打电话（ACTION_CALL）  —— 重，但一定能叫醒
 * 3. 前台服务常驻通知        —— 让系统别把我们的进程杀掉
 *
 * 【为什么阻断级不走通知，直接打电话】
 * 推送能不能被看到，取决于子女在不在看手机（可能在开会、开车、睡觉）。
 * 铃声能不能被听到，只取决于手机有没有电。
 *
 * 所以：关注级/橙色走通知，阻断级通知 + 自动拨号，两条一起上。
 *
 * 【两台手机怎么连】
 * 现在是"老人机 → 电脑上的 server.py → 子女机"。
 * 演示现场：两台手机连同一个热点，笔记本跑 server.py。
 * 以后换成云服务器，这里改一个地址就行，别的代码不用动。
 * ============================================================
 */
public class Notifier {

    private static final String TAG = "YinDun";
    private static final String CHANNEL_ID = "yindun_alert";
    private static final String CHANNEL_NAME = "银盾预警";

    /** 前台服务常驻通知的编号。固定一个，避免重复弹 */
    public static final int NOTIFY_FOREVER = 1001;
    /** 预警通知的编号 */
    public static final int NOTIFY_ALERT = 1002;

    // ==========================================================
    //  一、通知渠道（安卓 8 以后必须有）
    // ==========================================================
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        // IMPORTANCE_HIGH：横幅弹出 + 响铃，这是最高优先级
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("老人手机上的风险提醒");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    // ==========================================================
    //  二、前台服务常驻通知："银盾守护中"
    // ==========================================================
    /**
     * 状态栏里那条去不掉的通知。
     *
     * 【为什么要留一条赶不走的东西】
     * 因为国产系统（小米/OPPO/vivo/华为）会清理后台进程。没有前台服务，
     * App 装上去过两小时就死了，第二天老人被骗，一点反应都没有。
     *
     * 有一条常驻通知，系统把它当"用户知道它在跑"的进程，一般不动它。
     * 顺带还有个好处：老人一低头就看到"守护中"，有安全感。
     */
    public static Notification buildForeground(Context ctx) {
        ensureChannel(ctx);

        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);

        return b.setContentTitle("银盾守护中")
                .setContentText("正在看着陌生号码和动钱的 App")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)              // 划不掉
                .setContentIntent(pi)
                .build();
    }

    // ==========================================================
    //  三、预警：通知子女 + 推后端
    // ==========================================================
    /**
     * @param level none/watch/warn/high/block
     */
    public static void alertChild(final Context ctx, final String level,
                                  final String title, final String body) {
        alertChild(ctx, null, level, title, body);
    }

    /**
     * 带"事件类型"的版本。
     *
     * 【为什么需要 kind】
     * 子女端要把一通电话里发生的事串成时间轴（陌生来电→复问→验证码→
     * 打开银行→红屏→呼叫女儿）。光有标题和正文只能靠字符串猜步骤，
     * 改一句文案就断；所以这里显式带上一个稳定的英文标识，
     * 顺序、图标、文案全由子女端决定，老人端只负责"什么时候发生了什么事"。
     *
     * @param kind 见 K_ 常量；null = 普通预警，不进时间轴的特殊标注
     */
    public static void alertChild(final Context ctx, final String kind, final String level,
                                  final String title, final String body) {
        ensureChannel(ctx);

        // 阻断级用小图标 + 不让用户轻易划掉（秒级可能就要用）
        boolean blocking = "block".equals(level) || "high".equals(level);

        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 1, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);

        Notification n = b.setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .setOngoing(blocking)
                .setContentIntent(pi)
                .build();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        // 【老人机不弹这条通知】
        // 这条通知的收件人是子女（"妈妈可能正在被骗"），在子女机上是提醒，
        // 在老人机上就是一句莫名其妙的话——而且它会正好盖在盖屏顶端，
        // 把"验证码已被遮住"那张红屏挡掉一截。
        // 老人机这边该做的两件事（盖屏、拨号）由 Guard/Shield 负责，不走通知。
        if (nm != null && !isElderPhone(ctx)) nm.notify(NOTIFY_ALERT, n);

        // 同时推给后端，让它转给子女的手机
        pushToBackend(ctx, kind, level, title, body);

        // 阻断级：直接拨号（这才是真正能叫醒人的那一招）
        if ("block".equals(level)) {
            callChild(ctx);
        }
    }

    // ==========================================================
    //  四、打电话给子女
    // ==========================================================
    /**
     * 读出"女儿号码"配置，拨出去。
     *
     * 【为什么不用发短信】
     * 短信可以晚十分钟看，电话不能。而且阻断级可能只剩 90 秒。
     *
     * 【注意】
     * 这个号码在绑定阶段让子女填，存在本地。改号码在设置页改。
     */
    /**
     * 没绑定子女号码时用的演示号码。
     *
     * 【为什么宁可拨个假号，也不能什么都不做】
     * 以前这里是 return —— 结果盖屏上写着"打给女儿"，按下去一点动静都没有，
     * 老人以为女儿已经在路上了，其实电话根本没拨出去。
     * 这一条比不盖屏更危险：它给了老人一个"已经有人在救我"的错觉。
     *
     * 所以号码没配，也必须真的拨出去。演示时拨这个号，
     * 电话界面会真的跳出来、听筒里真的有回铃音——这是"打了"的唯一证据。
     */
    public static final String DEMO_CHILD_NUMBER = "13800138000";

    /** 当前要拨的号码：优先用绑定的，没绑就用演示号码 */
    public static String dialTarget(Context ctx) {
        String num = getChildNumber(ctx);
        if (num == null || num.trim().length() < 5) return DEMO_CHILD_NUMBER;
        return num.trim();
    }

    /** 有没有真的绑定过子女号码（决定盖屏上写什么文案） */
    public static boolean childBound(Context ctx) {
        String num = getChildNumber(ctx);
        return num != null && num.trim().length() >= 5;
    }

    /**
     * 能不能自动拨号：绑定了紧急联系人才行。
     * 演示号码只在模拟器上兜底 —— 真机上没绑号码就拨 13800138000，等于打给一个陌生人。
     */
    public static boolean canDial(Context ctx) {
        return childBound(ctx) || Guard.isEmulator();
    }

    public static void callChild(Context ctx) {
        if (!canDial(ctx)) {
            // 真机未设置紧急联系人：打开拨号盘，让老人自己拨，并提示去设置
            Log.w(TAG, "未设置紧急联系人，不自动拨号，改为打开拨号盘");
            try {
                Intent d = new Intent(Intent.ACTION_DIAL);
                d.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(d);
            } catch (Exception ignored) {
            }
            return;
        }
        String num = dialTarget(ctx);
        if (!childBound(ctx)) {
            Log.w(TAG, "模拟器上未绑定子女号码，改用演示号码拨出 " + num);
        }
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + num));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            Log.i(TAG, "已自动拨给子女 " + num);
            // 时间轴的最后一步。必须在 startActivity 之后发——
            // 拨号被系统拒了（没权限/没号码）就走不到这里，
            // 子女端也就不会看到一句"已自动呼叫女儿"的假消息。
            pushToBackend(ctx, K_CALL_CHILD, "high", "银盾已自动呼叫家人",
                    (childBound(ctx) ? "已自动拨出" : "未绑定家人号码，演示号码拨出") + "：" + num);
        } catch (Exception e) {
            Log.e(TAG, "自动拨号失败（缺 CALL_PHONE 权限？）", e);
        }
    }

    // ==========================================================
    //  五、配置读写
    // ==========================================================
    private static final String SP = "yindun";

    /** 这台机器是老人机还是子女机（默认按老人机算，宁可少弹不少弹） */
    public static boolean isElderPhone(Context ctx) {
        return "elder".equals(ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .getString("role", "elder"));
    }

    public static String getChildNumber(Context ctx) {
        return ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString("child_number", "");
    }

    public static void setChildNumber(Context ctx, String num) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().putString("child_number", num).apply();
    }

    public static String getBackend(Context ctx) {
        String v = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .getString("backend", "");
        if (v == null || v.trim().length() < 8) return DrillNet.DEFAULT_BASE;
        return v.trim();
    }

    public static void setBackend(Context ctx, String url) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().putString("backend", url).apply();
    }

    // ==========================================================
    //  六、推给后端（子女机从这里收）
    // ==========================================================
    // ==========================================================
    //  三·一、事件类型（子女端时间轴用）
    // ==========================================================
    /** 陌生号码正在响铃 */
    static final String K_RING = "ring";
    /** 接通了 */
    static final String K_OFFHOOK = "offhook";
    /** 通话满 5 分钟的复问及回答 */
    static final String K_ASK5 = "ask5";
    /** 通话中 / 刚挂断时收到验证码 */
    static final String K_CODE = "code";
    /** 打开了银行或支付类 App */
    static final String K_BANK = "bank";
    /** 红屏拦截已盖上 */
    static final String K_SHIELD = "shield";
    /** 自动拨给子女 */
    static final String K_CALL_CHILD = "call_child";
    /** 挂断后的录音复盘结论 */
    static final String K_REVIEW = "review";
    /** 可疑短信 */
    static final String K_SMS = "sms";
    /** 装了非应用商店的 App */
    static final String K_INSTALL = "install";
    /** 组合判据升级（陌生号 + 时长 + 验证码 + 银行 App 等） */
    static final String K_JUDGE = "judge";

    /**
     * 一次 HTTP POST，把预警发给电脑上跑着的 server.py。
     *
     * 【为什么必须放在子线程】
     * 安卓禁止主线程做网络请求，直接崩。
     *
     * 【失败怎么办】
     * 只记日志，不重试、不弹错。
     * 因为病人不会等我们重试——这一刻更重要的是本机的盖屏和拨号，
     * 那两件事不依赖网络，断网也能工作。
     */
    private static void pushToBackend(Context ctx, String level,
                                      String title, String body) {
        pushToBackend(ctx, null, level, title, body);
    }

    /**
     * @param kind 事件类型，见 K_ 常量；null = 普通预警
     */
    private static void pushToBackend(Context ctx, String kind, String level,
                                      String title, String body) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("level", level);
            o.put("title", title);
            o.put("body", body);
            o.put("device", Build.MODEL);
            o.put("time", System.currentTimeMillis());

            // 【2026-11-11 补】原来这里既没带 elderId 也没带 callId：
            //   · elderId 缺失时云端一律按 'demo' 落库，多老人场景会全部串到一个桶里；
            //   · callId 缺失时子女端只能按时间猜"这几条是不是一回事"，猜不准。
            // 两件都补上。elderId 取配对里登记的那一个，没绑定时 Pair.id 会退回 demo。
            o.put("elderId", Pair.id(ctx));

            String cid = CallSession.id();
            if (cid != null) o.put("callId", cid);
            String num = CallSession.number();
            if (num != null) o.put("number", num);
            if (kind != null) o.put("kind", kind);

            postJson(ctx, "/api/alert", o);
        } catch (Exception e) {
            Log.w(TAG, "组包失败：" + e.getMessage());
        }
    }

    /**
     * 老人点了「认识」之后，把这件事告诉云端。
     *
     * 【为什么发两个请求，而不是一个】
     *   /api/known —— 语义是"状态"：这个号码 15 分钟内别再问他了
     *   /api/alert —— 语义是"消息"：子女端要弹一张卡，让他知道发生了什么
     *
     * 两条各自独立。少发第一条，防护不受影响（只是下通还问）；
     * 少发第二条，子女就不知道他点过这个按钮——那才是真的损失。
     */
    public static void pushKnown(Context ctx, String number, String title, String body) {
        try {
            org.json.JSONObject k = new org.json.JSONObject();
            k.put("elderId", Pair.id(ctx));
            k.put("number", number);
            postJson(ctx, "/api/known", k);
        } catch (Exception ignored) {
        }
        pushToBackend(ctx, "watch", title, body);
    }

    /**
     * 通话中复问（第二次询问）的回答，推给子女端。
     *
     * 【为什么不用 pushKnown】
     * pushKnown 会顺带 POST /api/known，那是"这个号码 15 分钟别再问"的旧协议。
     * 2026-11-07 起免打扰已经取消，复问的回答只是一条消息，不是状态。
     *
     * @param known true = 老人说认识；false = 老人说不认识
     */
    public static void pushInCallAnswer(Context ctx, boolean known, String number) {
        if (known) {
            pushToBackend(ctx, K_ASK5, "watch", "妈妈确认通话对方为熟人（通话已超过 5 分钟）",
                    "通话超过 5 分钟时再次确认，妈妈选择了「认识」。\n"
                            + "熟人提出钱款要求同样需要核实，建议您致电确认。");
        } else {
            pushToBackend(ctx, K_ASK5, "warn", "妈妈表示不认识通话对方",
                    "与陌生号码通话已超过 5 分钟，再次确认时妈妈选择了「不认识」。\n"
                            + "此类情况风险较高，建议立即致电。");
        }
    }

    /** 统一的 POST 出口：所有上报都从这里走，省得每加一个接口就抄一遍 */
    private static void postJson(final Context ctx, final String path,
                                 final org.json.JSONObject o) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String base = getBackend(ctx);
                    URL url = new URL(base + path);
                    conn = (HttpURLConnection) url.openConnection();
                    Pair.sign(ctx, conn);
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                    OutputStream os = conn.getOutputStream();
                    os.write(o.toString().getBytes("UTF-8"));
                    os.close();

                    int code = conn.getResponseCode();
                    Log.i(TAG, "推给后端 " + path + " 结果 " + code);

                    // 服务端收了但拒了（参数错、签名错、路由不存在）也算没送到。
                    // 2026-11 修过一版：当时 /api/alert 根本没注册，
                    // 每次上报都是 404，却被 catch 之外这话路静默放过，
                    // 表现就是"手机上弹了预警，子女端一条收不到"。
                    if (code < 200 || code >= 300) {
                        Outbox.add(ctx, path, o);
                    } else {
                        // 网通了，顺手把之前积压的也发走
                        Outbox.flush(ctx);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "后端推不出去（断网或没开服务）：" + path + " " + e.getMessage());
                    // 不再丢弃。存起来，等网络回来再补发。
                    Outbox.add(ctx, path, o);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    /**
     * 同步 POST，把服务端的响应原样带回来。调用方必须在子线程里调。
     *
     * 【和 postJson 的区别】
     * postJson 是"喊一嗓子就走"——预警推不出去也不影响本机防护。
     * 这个必须等结果：老人转发的短信要拿服务端的判定来显示，
     * 手机上显示的级数必须和子女端收到的是同一个，
     * 否则会出现"手机说到第 3 步了，子女端一条都没收到"。
     *
     * 超时给得比 postJson 长，因为云端要跑一遍模型。
     *
     * @return 成功的响应体；失败（断网 / 4xx / 5xx）返回 null
     */
    public static String postJsonSync(Context ctx, String path,
                                      org.json.JSONObject o, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            String base = getBackend(ctx);
            URL url = new URL(base + path);
            conn = (HttpURLConnection) url.openConnection();
            Pair.sign(ctx, conn);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            OutputStream os = conn.getOutputStream();
            os.write(o.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String body = new String(bos.toByteArray(), "UTF-8");
            Log.i(TAG, "同步请求 " + path + " 结果 " + code + " / " + body.length() + " 字节");
            return (code >= 200 && code < 300) ? body : null;
        } catch (Exception e) {
            Log.w(TAG, "同步请求失败 " + path + "：" + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
