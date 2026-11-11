package com.yindun.guard;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * CallRecord —— 通话录音复盘（挂断之后）
 * ============================================================
 * 【它干什么】
 *   陌生来电挂断后，去找手机系统电话自带的通话录音（荣耀/华为/小米等国行机型
 *   打开"自动通话录音"后会自动生成），传给云端：转文字 → 大模型判断是不是诈骗。
 *   判成有风险：老人手机弹提醒；之后 30 分钟内打开手机银行，照样红屏。
 *
 * 【它不干什么】
 *   不自己录音（安卓 9 起第三方 App 录不了通话），不参与通话中的实时拦截。
 *   实时那一层仍然只看动作、不读内容，断网照样能拦。
 *
 * 【为什么挂断后还有用】
 *   骗子常常让老人"挂了电话去银行 App 操作"。挂断到转账之间有几分钟到几小时，
 *   复盘结论出来，正好赶在钱出门之前拉响警报。
 *
 * 【隐私】
 *   · 只处理陌生号码，通讯录里的人一律不碰
 *   · 需要老人在首页手动开启（同时授予"所有文件访问"）
 *   · 录音原文件不动、不复制；云端只在内存里转写，不存音频、不存全文
 * ============================================================
 */
public class CallRecord {

    private static final String TAG = "YinDun";
    private static final String SP = "yindun";

    /** 判成有风险后，多长时间内打开银行还要拦 */
    private static final long RISK_WINDOW_MS = 30 * 60 * 1000L;
    /** 挂断后最多等多久让系统把录音写完 */
    private static final int POLL_TIMES = 20;
    private static final long POLL_GAP_MS = 3000;
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    /**
     * 各家系统电话存录音的地方（相对外部存储根目录）。
     * 荣耀老机型（EMUI / Magic UI）放第一位，这次比赛按它适配。
     * 真机上请用 adb 实测确认，不对就在这里加一行，或者设置 rec_dir 覆盖。
     */
    private static final String[] DIRS = {
            "Sounds/CallRecord",                      // 华为 / 荣耀 EMUI 9+、Magic UI
            "record",                                 // 更老的 EMUI
            "Recordings/Call",                        // 部分 MagicOS / 原生风格
            "Recordings/CallRecord",
            "Music/Recordings/Call Recordings",       // ColorOS
            "MIUI/sound_recorder/call_rec",           // 小米
            "CallRecord",
    };

    private static final String[] EXTS = {".mp3", ".m4a", ".amr", ".aac", ".wav", ".3gp", ".ogg", ".awb"};

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** 风险窗口：截止时间和那通电话的号码 */
    private static volatile long riskUntil = 0;
    private static volatile String riskNumber = null;
    /**
     * 这条风险窗口是谁开的。
     *   null    = 录音复盘判出来的（"那通电话有问题"）
     *   非 null = 子女远程锁的转账（"家里人不让你这会儿动钱"）
     * 两种来源要说两种话：远程锁定时老人可能压根没接到诈骗电话，
     * 跟她说"刚才那通电话有诈"就是编造。
     */
    private static volatile String riskFrom = null;
    /** 上一个处理过的文件，防止同一份录音传两次 */
    private static volatile String lastFile = null;

    // ==========================================================
    //  一、开关与权限
    // ==========================================================

    /** 老人有没有在首页开启这个功能（开启即同意） */
    public static boolean enabled(Context c) {
        return sp(c).getBoolean("rec_on", false);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("rec_on", on).apply();
    }

    /**
     * 能不能读别的 App 生成的录音文件。
     *   安卓 11+：要"所有文件访问"
     *   安卓 10 及以下：READ_EXTERNAL_STORAGE（安卓 10 还要清单里的 requestLegacyExternalStorage）
     */
    public static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 功能真正在工作：开了，并且有权限 */
    public static boolean active(Context c) {
        return enabled(c) && hasAccess(c);
    }

    // ==========================================================
    //  二、风险窗口（Guard.onBankForeground 用）
    // ==========================================================

    public static boolean riskActive() {
        return System.currentTimeMillis() < riskUntil;
    }

    public static String riskNumber() {
        return riskNumber;
    }

    public static String riskFrom() {
        return riskFrom;
    }

    /**
     * 子女远程开一段转账保护窗口。
     *
     * 不录音、不读内容、也不需要那通电话存在 —— 就是"这 N 分钟里，
     * 老人一打开手机银行就先盖一层屏，同时通知子女"。
     *
     * @param minutes 时长（分钟），调用方已经夹在 5~120 之间
     * @param from    谁开的，用于界面文案（"女儿"）
     */
    public static void remoteLock(Context c, int minutes, String from) {
        riskUntil = System.currentTimeMillis() + Math.max(1, minutes) * 60 * 1000L;
        riskNumber = null;
        riskFrom = (from == null || from.trim().length() == 0) ? "家人" : from.trim();
        Log.i(TAG, "子女远程锁定转账 " + minutes + " 分钟（" + riskFrom + "）");
    }

    // ==========================================================
    //  三、入口：Guard 在一通接通过的电话挂断时调用
    // ==========================================================

    /**
     * @param number  对方号码
     * @param who     号码身份，只处理 stranger
     * @param startAt 接通时刻（毫秒）
     */
    public static void onCallEnded(final Context ctx, final String number, String who, final long startAt) {
        if (!NumberInfo.STRANGER.equals(who)) return;
        if (!enabled(ctx)) return;
        if (!hasAccess(ctx)) {
            Log.w(TAG, "录音复盘已开启，但没有文件访问权限，跳过");
            return;
        }
        final long endAt = System.currentTimeMillis();
        final long durSec = Math.max(0, (endAt - startAt) / 1000);
        Log.i(TAG, "陌生来电挂断，开始找通话录音 num=" + number + " 时长=" + durSec + "秒");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = waitForRecording(ctx, startAt);
                    if (f == null) {
                        Log.w(TAG, "等了 " + (POLL_TIMES * POLL_GAP_MS / 1000) + " 秒没找到录音："
                                + "系统电话的自动录音开了吗？路径在不在 DIRS 里？");
                        return;
                    }
                    lastFile = f.getAbsolutePath();
                    upload(ctx, f, number, durSec);
                } catch (Exception e) {
                    Log.e(TAG, "录音复盘出错", e);
                }
            }
        }, "yd-callrecord").start();
    }

    // ==========================================================
    //  四、找录音
    // ==========================================================

    /** 挂断后轮询：找到"这通电话期间写过的"最新音频，并等它不再变大 */
    private static File waitForRecording(Context ctx, long startAt) throws InterruptedException {
        long lastSize = -1;
        File cand = null;
        for (int i = 0; i < POLL_TIMES; i++) {
            Thread.sleep(POLL_GAP_MS);
            File f = newest(ctx, startAt);
            if (f == null) continue;
            if (cand != null && cand.equals(f) && f.length() == lastSize && lastSize > 0) {
                Log.i(TAG, "找到通话录音 " + f.getAbsolutePath() + "（" + f.length() / 1024 + "KB）");
                return f;
            }
            cand = f;
            lastSize = f.length();
        }
        return null;
    }

    /** 所有候选目录里，修改时间晚于"接通前 10 秒"的最新音频文件 */
    private static File newest(Context ctx, long startAt) {
        File root = Environment.getExternalStorageDirectory();
        File best = null;
        for (String d : dirs(ctx)) {
            File dir = d.startsWith("/") ? new File(d) : new File(root, d);
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (!f.isFile() || !isAudio(f.getName())) continue;
                if (f.lastModified() < startAt - 10000) continue;
                if (f.getAbsolutePath().equals(lastFile)) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        return best;
    }

    /** 设置里填了 rec_dir 就只看那一个目录，否则看默认列表 */
    private static String[] dirs(Context ctx) {
        String custom = sp(ctx).getString("rec_dir", "");
        if (custom != null && custom.trim().length() > 0) return new String[]{custom.trim()};
        return DIRS;
    }

    private static boolean isAudio(String name) {
        String n = name.toLowerCase();
        for (String e : EXTS) if (n.endsWith(e)) return true;
        return false;
    }

    // ==========================================================
    //  五、上传 + 处理结论
    // ==========================================================

    private static void upload(Context ctx, File f, String number, long durSec) {
        if (f.length() > MAX_BYTES) {
            Log.w(TAG, "录音太大（" + f.length() / 1024 / 1024 + "MB），不传");
            return;
        }
        HttpURLConnection conn = null;
        try {
            JSONObject o = new JSONObject();
            o.put("elderId", Pair.id(ctx));
            o.put("number", number == null ? "" : number);
            o.put("durSec", durSec);
            o.put("name", f.getName());
            o.put("audio", Base64.encodeToString(readAll(f), Base64.NO_WRAP));
            // 复盘结论要挂回同一场事件：子女端时间轴上「挂断后复盘判定为诈骗」
            // 是这条链的最后一格，不带 id 的话它会变成一条孤立的卡片。
            String cid = CallSession.id();
            if (cid != null) o.put("callId", cid);
            // 演示转写稿：录音旁边的同名 .txt。云端只在没配语音识别时才用它，并会标明来源
            String demo = demoText(f);
            if (demo != null) o.put("demoText", demo);

            URL url = new URL(Notifier.getBackend(ctx) + "/api/callaudio");
            conn = (HttpURLConnection) url.openConnection();
            Pair.sign(ctx, conn);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(90000);     // 转写 + 大模型，长录音要几十秒
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String token = sp(ctx).getString("yd_token", "");
            if (token != null && token.length() > 0) conn.setRequestProperty("X-Yd-Token", token);

            OutputStream os = conn.getOutputStream();
            os.write(o.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = is == null ? "" : new String(readAll(is), "UTF-8");
            Log.i(TAG, "录音复盘上传结果 " + code + " " + (body.length() > 300 ? body.substring(0, 300) : body));
            if (code >= 200 && code < 300) handle(ctx, new JSONObject(body), number);
        } catch (Exception e) {
            Log.w(TAG, "录音复盘上传失败（断网或云端没开）：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 云端的结论落到老人手机上 */
    private static void handle(final Context ctx, final JSONObject r, final String number) {
        final String level = r.optString("level", "none");
        final int stage = r.optInt("stage", 0);
        final boolean pushed = r.optBoolean("pushed", false);
        final String type = r.optString("type", "");
        final String reason = r.optString("reason", "");
        final JSONArray ev = r.optJSONArray("evidence");

        int rk = rank(level);
        if (rk < rank("warn")) {
            Log.i(TAG, "录音复盘：S" + stage + "/" + level + "，不打扰老人");
            return;
        }

        // high / block：开 30 分钟风险窗口，期间打开银行就红屏
        if (rk >= rank("high")) {
            riskUntil = System.currentTimeMillis() + RISK_WINDOW_MS;
            riskNumber = number;
            riskFrom = null;    // 录音复盘开的窗口，不是子女远程锁的
            Log.i(TAG, "录音复盘判为 " + level + "，30 分钟内打开银行将直接盖屏");
            // ④：把这套套路记下来，下次演练提醒就从它开始。
            // 只记类型（"冒充公检法"），不记原话 —— 台词云端会重编，原话留着是多余的隐私风险。
            DrillPending.remember(ctx, type, stage);
            // 次日单独提醒一次（交接文档 ④ 的"次日推送"）。和每周那个闹钟并存，互相不覆盖。
            DrillAlarm.scheduleTomorrow(ctx);
        }

        final StringBuilder why = new StringBuilder("通话录音复盘：");
        why.append(type.length() > 0 ? type : "疑似诈骗");
        if (reason.length() > 0) why.append(" · ").append(reason);
        if (ev != null && ev.length() > 0) why.append(" · 「").append(ev.optString(0)).append("」");

        final String[] quotes = new String[ev == null ? 0 : ev.length()];
        for (int i = 0; i < quotes.length; i++) quotes[i] = ev.optString(i);
        final String advice = r.optString("advice", "");
        main.post(new Runnable() {
            @Override
            public void run() {
                // 底部升起的复盘卡：骗子走到第几步 + 对方原话 + 打给女儿
                // （文案只说真在发生的事：推送成功才写"女儿已经收到提醒"）
                AlertSheet.show(ctx, level, stage, type, quotes, advice, pushed);
                Vibrate.remind(ctx, Vibrate.screenOff(ctx));
                EventBus.pushAlert(level, why.toString(), "S" + stage, stage * 20);
            }
        });
    }

    // ==========================================================
    //  六、工具
    // ==========================================================

    private static String demoText(File audio) {
        String n = audio.getName();
        int dot = n.lastIndexOf('.');
        File txt = new File(audio.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".txt");
        if (!txt.isFile() || txt.length() > 32 * 1024) return null;
        try {
            return new String(readAll(txt), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static int rank(String level) {
        if ("block".equals(level)) return 4;
        if ("high".equals(level)) return 3;
        if ("warn".equals(level)) return 2;
        if ("watch".equals(level)) return 1;
        return 0;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
