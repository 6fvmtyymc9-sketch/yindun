package com.yindun.guard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * ============================================================
 *  CommandLink —— 老人端接收「子女远程干预」指令的那条线
 * ============================================================
 *
 * 【为什么老人端要有长连接】
 * 以前老人端只往上推（上报预警），收不到任何东西。子女在子女端点「提醒妈妈」，
 * 老人手机上是不会响的 —— 那条链路根本不存在。
 *
 * 连的是云端已有的 /api/stream（和子女端同一条 SSE），收到 type=command 就执行。
 *
 * 【为什么还留一条轮询】
 * SSE 靠一条长连接活着，而国产 ROM 杀后台、切网络、进电梯都会把它掐断。
 * 演示现场最怕的就是"子女按了按钮，老人手机一动不动"。
 * 所以：连续 3 次连不上就改用轮询（5 秒一次），60 秒后再试着切回长连接。
 * 两条路都走 /api/command，指令在云端留着，晚到总比不到好。
 *
 * 【为什么去重】
 * 同一条指令可能被推一次、又被轮询取一次；服务端重连后也可能重发。
 * 用 (id, at) 这一对认指令：id 相同但 at 不同，说明是另一条指令（服务端重启后 id 会从 1 重来）。
 */
final class CommandLink {

    private static final String TAG = "YinDun";

    /** 服务端 15 秒心跳一次；45 秒没动静就当这条连接死了 */
    private static final int READ_TIMEOUT_MS = 45000;
    private static final int RECONNECT_MS = 3000;
    private static final int POLL_MS = 5000;
    /** 换到轮询后，多久再试着切回长连接 */
    private static final long POLL_PHASE_MS = 60000;

    private static volatile boolean started = false;
    private static volatile Thread thread;
    /** 界面操作统一回到主线程 */
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** 已执行过的最后一条指令 */
    private static volatile long lastId = 0;
    private static volatile long lastAt = 0;

    private CommandLink() { }

    static void start(Context c) {
        if (started) return;
        started = true;
        final Context app = c.getApplicationContext();
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(app); }
        }, "yindun-command");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "子女指令通道已启动");
    }

    static void stop() {
        started = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    // ==========================================================
    //  连接循环
    // ==========================================================

    private static void loop(Context app) {
        int fails = 0;
        long pollUntil = 0;
        while (started) {
            if (System.currentTimeMillis() < pollUntil) {
                pollOnce(app);
                sleep(POLL_MS);
                continue;
            }
            try {
                stream(app);          // 正常情况阻塞在这里，直到连接断开
                fails = 0;
            } catch (Throwable e) {
                fails++;
                Log.w(TAG, "指令通道断开（第 " + fails + " 次）：" + e);
            }
            if (fails >= 3) {
                fails = 0;
                pollUntil = System.currentTimeMillis() + POLL_PHASE_MS;
                Log.w(TAG, "改用轮询 " + (POLL_PHASE_MS / 1000) + " 秒，之后再试长连接");
            }
            sleep(RECONNECT_MS);
        }
    }

    /** 订阅实时流，阻塞读取，直到连接断掉 */
    private static void stream(Context c) throws Exception {
        String base = DrillNet.base(c);
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/api/stream?elderId="
                + URLEncoder.encode(Pair.id(c), "UTF-8");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Cache-Control", "no-cache");
        Pair.sign(c, conn);
        conn.connect();

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        Log.i(TAG, "指令通道已连上 " + url);

        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder buf = new StringBuilder();
        String line;
        while (started && (line = r.readLine()) != null) {
            if (line.length() == 0) {                 // 空行 = 一帧结束
                if (buf.length() > 0) {
                    onFrame(c, buf.toString());
                    buf.setLength(0);
                }
            } else if (line.startsWith(":")) {
                // 心跳注释，忽略
            } else if (line.startsWith("data:")) {
                buf.append(line.substring(5).trim());
            }
        }
        r.close();
        conn.disconnect();
    }

    /** 兜底：把 after 之后没收到过的指令取回来 */
    private static void pollOnce(Context c) {
        try {
            JSONObject o = DrillNet.get(c, "/api/command/poll?after=" + lastId
                    + "&elderId=" + URLEncoder.encode(Pair.id(c), "UTF-8"));
            if (o == null || !o.optBoolean("ok")) return;
            JSONArray arr = o.optJSONArray("list");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m != null) apply(c, m.optLong("id", 0), m.optLong("at", 0),
                        m.optString("action", ""), m.optInt("minutes", 30), m.optString("from", "家人"));
            }
        } catch (Throwable e) {
            Log.w(TAG, "指令轮询失败：" + e);
        }
    }

    // ==========================================================
    //  收到一帧
    // ==========================================================

    private static void onFrame(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"command".equals(o.optString("type", ""))) return;   // alert/summary 不归这里管
            apply(c, o.optLong("id", 0), o.optLong("at", 0),
                    o.optString("action", ""), o.optInt("minutes", 30), o.optString("from", "家人"));
        } catch (Exception e) {
            Log.w(TAG, "指令帧解析失败：" + json, e);
        }
    }

    private static synchronized void apply(Context c, long id, long at, String action, int minutes, String from) {
        if (id <= 0 || action.length() == 0) return;
        if (id == lastId && at == lastAt) return;      // 重复推送 / 推送+轮询各来一次
        lastId = Math.max(lastId, id);
        lastAt = at;
        Log.i(TAG, "收到子女指令 " + action + "（来自 " + from + "）");

        if ("nudge".equals(action)) {
            Nudge.show(c, from);
        } else if ("lock".equals(action)) {
            final int m = minutes <= 0 ? 30 : minutes;
            CallRecord.remoteLock(c, m, from);
            final String who = from;
            // 提示条也是界面，同样必须回主线程（这条 apply 从 SSE 线程进来）
            main.post(new Runnable() {
                @Override public void run() {
                    TipBar.showBottom(c, "warn",
                            who + "开启了转账保护",
                            m + " 分钟内打开手机银行会先拦一下");
                }
            });
            EventBus.pushAlert("warn", from + " 远程开启了 " + m + " 分钟转账保护", "S4", 90);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
