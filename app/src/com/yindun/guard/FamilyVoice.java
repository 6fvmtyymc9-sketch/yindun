package com.yindun.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * FamilyVoice —— 把女儿自己录的那一句，提前存到这台手机上
 * ============================================================
 * 【它解决的不是"播放"，是"到时候必须有"】
 *
 * 盖红屏那一刻，我们只有几秒钟。如果那时才去下载：
 *   · 老人手机在 4G 弱网下要 1~3 秒，甚至失败；
 *   · 钱早在这一两秒里转出去了。
 *
 * 所以真正的动作在平时：开机拉一次、之后每 6 小时问一次
 * "云端那份换了没有"，换了才下载，下载完整个文件才替换。
 *
 * 【为什么先问 meta 再下】
 * 音频一般 100~300KB。为了判断"要不要下"先把音频拖一遍，
 * 流量和等待都不划算。meta 只有几十字节。
 *
 * 【失败了怎么办】
 * 静默失败，保留上一次的缓存（如果有）。播放时 Speak 会退到
 * assets 里的预录音，再退到 TTS——三级兜底，绝不因为网络问题哑火。
 *
 * 【存哪】
 * filesDir/voice/family.<ext> —— 应用私有目录，不用申请存储权限，
 * 卸载即清除，也不会被别的 App 读到。
 * ============================================================
 */
class FamilyVoice {

    private static final String TAG = "YinDun";
    private static final String SP = "yindun_family_voice";
    private static final String K_AT = "voice_at";       // 已缓存那份的云端时间戳
    private static final String K_FILE = "file_name";    // 文件名（含扩展名）
    private static final String K_FROM = "from";         // 谁录的（女儿/儿子），只用于日志

    /** 6 小时问一次。一天 4 次，每次几十字节，可以忽略 */
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;

    private static volatile long lastCheck = 0;
    private static volatile boolean checking = false;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 已经缓存好的那个文件；没有就返回 null */
    static File cached(Context c) {
        SharedPreferences s = sp(c);
        String name = s.getString(K_FILE, "");
        if (name == null || name.length() == 0) return null;
        File f = new File(new File(c.getApplicationContext().getFilesDir(), "voice"), name);
        if (!f.exists() || f.length() < 1024) return null;
        return f;
    }

    static String from(Context c) {
        return sp(c).getString(K_FROM, "家人");
    }

    /**
     * 开机 / 每次进前台都调一次。内部有节流，不会到处发请求。
     *
     * @param force true = 忽略 6 小时间隔，并且无视"手上这份已经是最新的"（设置页点"立即同步"用）
     */
    static void refresh(final Context c, final boolean force) {
        if (checking) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastCheck < CHECK_INTERVAL_MS) return;
        lastCheck = now;
        checking = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doRefresh(c.getApplicationContext(), force);
                } catch (Exception e) {
                    Log.w(TAG, "家人录音同步失败（不影响使用）：" + e.getMessage());
                } finally {
                    checking = false;
                }
            }
        }, "family-voice").start();
    }

    private static void doRefresh(Context c, boolean manual) {
        // ---------- 1. 先问：有没有、是不是新的 ----------
        String base = base(c);
        if (base == null) return;

        JSONObject meta = getJson(c, base + "/api/voice/meta?elderId=" + Pair.id(c));
        if (meta == null || !meta.optBoolean("ok")) return;

        if (!meta.optBoolean("exists")) {
            Log.i(TAG, "云端还没有家人录音，先不缓存（红屏会用 TTS 兜底）");
            return;
        }
        long at = meta.optLong("at", 0);
        long have = sp(c).getLong(K_AT, 0);
        if (!manual && at <= have && cached(c) != null) {
            return;   // 手上这份就是最新的
        }

        // ---------- 2. 下载整份，写临时文件，成功才替换 ----------
        byte[] data = getBytes(c, base + "/api/voice?elderId=" + Pair.id(c), 15000);
        if (data == null || data.length < 1024) {
            Log.w(TAG, "家人录音下载失败，继续用上次缓存的那份");
            return;
        }

        String ext = extOf(meta.optString("mime", "audio/mp4"));
        String name = "family." + ext;
        File dir = new File(c.getFilesDir(), "voice");
        try { dir.mkdirs(); } catch (Exception ignored) { }

        File tmp = new File(dir, name + ".tmp");
        File out = new File(dir, name);
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(tmp);
            fo.write(data);
            fo.flush();
            fo.close();
            fo = null;
            // 换扩展名时把旧后缀删掉，免得 cached() 找到两个。
            // 注意别把自己的临时文件也删了 —— 上面刚写完 family.wav.tmp，
            // 这条循环如果按"以 family. 开头"删，第一个删掉的就是正在用的那个。
            File[] olds = dir.listFiles();
            if (olds != null) {
                for (File f : olds) {
                    String fn = f.getName();
                    if (!fn.startsWith("family.")) continue;
                    if (fn.equals(name) || fn.endsWith(".tmp")) continue;
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            if (out.exists()) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            if (!tmp.renameTo(out)) {
                Log.w(TAG, "家人录音落盘失败（改名不成功）");
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "家人录音落盘失败：" + e.getMessage());
            try { if (fo != null) fo.close(); } catch (Exception ignored) { }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }

        sp(c).edit()
                .putLong(K_AT, at)
                .putString(K_FILE, name)
                .putString(K_FROM, meta.optString("from", "家人"))
                .apply();

        Log.i(TAG, "家人录音已缓存：" + name + " " + (data.length / 1024) + "KB（来自 "
                + meta.optString("from", "家人") + "）");
    }

    /** 手动"立即同步一次"：设置页点了就重新下一次，不管手上那份是不是最新 */
    static void syncNow(Context c) {
        lastCheck = 0;
        refresh(c, true);
    }

    // ------------------------------------------------------------------------
    //  播放：给 Speak 用
    // ------------------------------------------------------------------------

    private static MediaPlayer player;

    /**
     * 放家人录的那一句。
     *
     * @return true = 真的放出声音了；false = 没有缓存或放不出来（调用方继续走兜底）
     */
    static boolean play(Context c) {
        final File f = cached(c);
        if (f == null) return false;
        stop();
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stop();
                }
            });
            player.start();
            Log.i(TAG, "播放家人录音（本地缓存）：" + f.getName() + "，来自 " + from(c));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "家人录音播放失败，改走预录/TTS：" + e.getMessage());
            stop();
            return false;
        }
    }

    static void stop() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    // ------------------------------------------------------------------------
    //  网络
    // ------------------------------------------------------------------------

    private static String base(Context c) {
        String b = DrillNet.base(c);
        if (b == null || b.length() < 8) return null;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String extOf(String mime) {
        String m = mime == null ? "" : mime.toLowerCase();
        if (m.contains("wav")) return "wav";
        if (m.contains("mpeg") || m.contains("mp3")) return "mp3";
        if (m.contains("ogg")) return "ogg";
        if (m.contains("webm")) return "webm";
        if (m.contains("amr")) return "amr";
        return "m4a";
    }

    private static HttpURLConnection open(Context c, String url, int readTimeout) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        Pair.sign(c, conn);   // 老人端身份（演示模式不带）
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(readTimeout);
        conn.setRequestProperty("User-Agent", "YindunApp/" + Build.VERSION.SDK_INT);
        return conn;
    }

    private static JSONObject getJson(Context c, String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(c, url, 8000);
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return new JSONObject(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "查询家人录音失败：" + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] getBytes(Context c, String url, int readTimeout) {
        HttpURLConnection conn = null;
        try {
            conn = open(c, url, readTimeout);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "下载家人录音 HTTP " + code);
                return null;
            }
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return bo.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "下载家人录音失败：" + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
