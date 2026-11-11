package com.yindun.guard;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Outbox —— 上报失败后的本地待发队列
 * ============================================================
 * 【解决什么问题】
 * 原来 Notifier.postJson 推不出去就只记一行日志，然后忘掉。
 * 老人家里 WiFi 一断、或者路由器重启那两分钟里发生的预警，
 * 子女端永远收不到 —— 而且是"静默丢失"，事后谁也不知道丢过。
 *
 * 【为什么一定要落文件，不能放内存】
 * 上报发生在手机可能随时被系统杀掉的时刻。放内存里，进程一死队列就没了，
 * 等于没做。落盘才有意义。
 *
 * 【顺序为什么不能乱】
 * 预警有先后因果："妈妈正在和陌生号码通话" → "妈妈打开了手机银行"。
 * 如果补发时后一条先到，子女端看到的是"银行已打开"却不知道前面有过通话，
 * 会误判成孤立事件。所以队首发不出去就停住，不跳着发。
 *
 * 【什么时候补发】
 *   1. GuardService 启动时（开机、进程被拉活）
 *   2. 网络恢复时（NetworkCallback）
 *   3. 每次有新预警上报失败时（顺手再试一次，可能网刚好回来）
 * ============================================================
 */
public class Outbox {

    private static final String TAG = "YinDun";

    /** 队列文件名。JSON Lines，一行一条，方便追加和逐条删除。 */
    private static final String FILE = "outbox.jsonl";

    /** 上限。老人断网一整天也不会堆太多，真超了丢最旧的 —— 最新的那条最要紧。 */
    private static final int MAX = 200;

    /**
     * 服务端明确报错时的重试上限。
     *
     * 【为什么不给"连不上"计数】断网时每 60 秒会试一次，如果连"连不上"也算次数，
     * 老人家里断网 8 分钟（8 次）队列就被清空了 —— 那正好把最该留的东西丢掉。
     * 连不上就老老实实等着，网络回来自然会发出去。
     */
    private static final int MAX_TRIES = 8;

    /** 超过这个时长的积压就不发了：子女端收到 3 天前的一条预警，只会更糊涂。 */
    private static final long EXPIRE_MS = 24 * 3600 * 1000L;

    /** 补发线程互斥。同一时刻只允许一个线程在发，避免同一条发两遍。 */
    private static boolean flushing = false;

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    /**
     * 状态型接口：存的是"现在起一段时间内怎么处理"，不是"发生过什么"。
     * 补发这类请求，语义就错了。
     */
    private static boolean stateful(String path) {
        return path.startsWith("/api/known");
    }

    // ==========================================================
    //  入队
    // ==========================================================
    /**
     * 把一条没发出去的上报存起来。
     *
     * @param path 形如 /api/alert
     * @param body 已经组好的 JSON
     */
    public static synchronized void add(Context ctx, String path, JSONObject body) {
        if (path == null || body == null) return;
        // 状态型接口不补发。典型的是 /api/known："这个号码 15 分钟内别再问"。
        // 十几分钟后补发一条十几分钟前的状态，不但没用，还会把**当下**的判定
        // 顺带免打扰掉 15 分钟 —— 等于帮骗子把门打开。
        if (stateful(path)) {
            Log.i(TAG, "状态型上报不补发，直接丢弃：" + path);
            return;
        }
        try {
            List<String> lines = read(ctx);
            String rec = record(path, body, 0);

            // 同一条不重复入队：断网时可能连续触发几次同样的预警，
            // 全存下来会让子女端在补发时收到一屏重复消息。
            if (!lines.isEmpty() && key(lines.get(lines.size() - 1)).equals(key(rec))) {
                return;
            }

            lines.add(rec);
            while (lines.size() > MAX) {
                Log.w(TAG, "待发队列超过 " + MAX + " 条，丢弃最旧的一条");
                lines.remove(0);
            }
            write(ctx, lines);
            Log.i(TAG, "上报未成功，已存起来待补发：" + path + "（队列 " + lines.size() + " 条）");
        } catch (Exception e) {
            Log.w(TAG, "存待发队列失败：" + e.getMessage());
        }
    }

    /** 队列里还有几条没发出去。设置页和日志用得上。 */
    public static synchronized int size(Context ctx) {
        return read(ctx).size();
    }

    // ==========================================================
    //  补发
    // ==========================================================
    /** 起一条后台线程补发。调用方可以直接在任意线程调，不阻塞。 */
    public static void flush(final Context ctx) {
        if (ctx == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                flushNow(ctx.getApplicationContext());
            }
        }, "yd-outbox").start();
    }

    /**
     * 逐条补发。
     *
     * 【失败就停，不跳着发】见类注释里"顺序为什么不能乱"。
     * 【4xx 直接丢】参数错、签名错这类，重试一万次也一样，留着只会堵住后面的真消息。
     *            408 / 429 例外，那是"稍后再试"的意思。
     */
    private static void flushNow(Context ctx) {
        synchronized (Outbox.class) {
            if (flushing) return;
            if (read(ctx).isEmpty()) return;
            flushing = true;
        }

        int sent = 0;
        try {
            while (true) {
                String line;
                synchronized (Outbox.class) {
                    List<String> lines = read(ctx);
                    if (lines.isEmpty()) break;
                    line = lines.get(0);
                }

                JSONObject rec;
                try {
                    rec = new JSONObject(line);
                } catch (Exception broken) {
                    dropFirst(ctx);   // 文件被写坏了，别让它堵住整条队列
                    continue;
                }

                String path = rec.optString("path", "");
                JSONObject body = rec.optJSONObject("body");
                long at = rec.optLong("at", 0L);

                // 积压太久的直接丢。子女端看到"三天前的通话"，只会更糊涂，
                // 而且会让时间线错位。丢掉并记一笔，比留着一个没有意义的旧事件诚实。
                if (at > 0 && System.currentTimeMillis() - at > EXPIRE_MS) {
                    Log.w(TAG, "积压超过 24 小时，丢弃：" + path);
                    dropFirst(ctx);
                    continue;
                }

                int code = post(ctx, path, body, at);
                boolean ok = code >= 200 && code < 300;
                boolean fatal = code >= 400 && code < 500 && code != 408 && code != 429;

                if (ok || fatal) {
                    if (ok) {
                        sent++;
                    } else {
                        Log.w(TAG, "补发被服务端拒绝（" + code + "），丢弃：" + path);
                    }
                    dropFirst(ctx);
                    continue;
                }

                // 连不上（断网、服务没开）：不算失败次数，等下次。见 MAX_TRIES 的注释。
                if (code < 0) break;

                // 服务端 5xx：它自己出错了，累加次数，攒够就放弃
                int tries = rec.optInt("tries", 0) + 1;
                if (tries >= MAX_TRIES) {
                    Log.w(TAG, "补发失败 " + tries + " 次，丢弃：" + path);
                    dropFirst(ctx);
                    continue;
                }
                synchronized (Outbox.class) {
                    List<String> lines = read(ctx);
                    if (!lines.isEmpty()) {
                        try {
                            rec.put("tries", tries);
                        } catch (Exception ignored) {
                        }
                        lines.set(0, rec.toString());
                        write(ctx, lines);
                    }
                }
                break;
            }
        } catch (Exception e) {
            Log.w(TAG, "补发出错：" + e.getMessage());
        } finally {
            synchronized (Outbox.class) {
                flushing = false;
            }
        }

        if (sent > 0) {
            Log.i(TAG, "断网补发送出 " + sent + " 条，剩余 " + size(ctx) + " 条");
        }
    }

    private static int post(Context ctx, String path, JSONObject body, long at) {
        if (path == null || path.length() == 0 || body == null) return 400;
        HttpURLConnection conn = null;
        try {
            // 带上事件真正发生的时间。云端拿它和收到的时间一比，
            // 就能在子女端标明"这是断网补发的，不是刚刚发生的"。
            // 不能让它看起来像"妈妈刚刚才打开银行"——那是没发生的事。
            body.put("at", at);

            URL url = new URL(Notifier.getBackend(ctx) + path);
            conn = (HttpURLConnection) url.openConnection();
            Pair.sign(ctx, conn);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            return conn.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void dropFirst(Context ctx) {
        synchronized (Outbox.class) {
            List<String> lines = read(ctx);
            if (lines.isEmpty()) return;
            lines.remove(0);
            write(ctx, lines);
        }
    }

    // ==========================================================
    //  文件读写
    // ==========================================================
    private static String record(String path, JSONObject body, int tries) throws Exception {
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("body", body);
        o.put("at", System.currentTimeMillis());
        o.put("tries", tries);
        return o.toString();
    }

    /** 去重用的比对键：只比"发给谁"和"发了什么"，不比重试次数。 */
    private static String key(String rec) {
        try {
            JSONObject o = new JSONObject(rec);
            return o.optString("path", "") + "\u0001" + o.opt("body");
        } catch (Exception e) {
            return rec;
        }
    }

    private static List<String> read(Context ctx) {
        List<String> out = new ArrayList<String>();
        File f = file(ctx);
        if (!f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0) out.add(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "读待发队列失败：" + e.getMessage());
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void write(Context ctx, List<String> lines) {
        File f = file(ctx);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        OutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            StringBuilder sb = new StringBuilder();
            for (String s : lines) sb.append(s).append('\n');
            os.write(sb.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
            os = null;
            // 先写临时文件再改名：写到一半进程被杀，旧队列还在，不会两头空
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "写待发队列失败：" + e.getMessage());
            try {
                if (os != null) os.close();
            } catch (Exception ignored) {
            }
        }
    }
}
