package com.yindun.child;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 子女端的数据中枢：一条 SSE 长连接 + 一份内存里的时间线。
 *
 * 【为什么用 SSE 而不是轮询】
 * 轮询最快 3 秒一次，演示视频里会出现「老人手机已经响铃了，子女手机还在等」的空档，
 * 一眼就看出是假的。SSE 是服务端推，延迟在一秒以内，看起来才是同时发生的。
 *
 * 【为什么连接放在单例里】
 * Activity 会被销毁重建（切横竖屏、按 Home 再回来），连接不能跟着没。
 * 进程由 WatchService 前台服务保着，所以这条线程能一直活下去。
 */
public final class Hub {

    private static final String TAG = "YinDunChild";

    /** 服务端心跳 15 秒一次；45 秒一点动静都没有，就当这条连接死了 */
    private static final int READ_TIMEOUT_MS = 45000;
    private static final int RECONNECT_MS = 3000;

    /**
     * 上一次配对的结果，给界面用。
     * pair() 的返回值只表达"成功还是失败原因"，但界面还需要知道
     * "这是新老人还是原有老人换了凭证"——新增时应该问一句怎么称呼。
     * 不想为这个把 pair() 的返回值改成对象，就放在这两个字段里。
     */
    public static String lastPairElderId = "";
    public static boolean lastPairIsNew = false;

    private static final Hub I = new Hub();

    public static Hub get() { return I; }

    /** 界面只实现这三个回调，不需要知道底下是 SSE 还是别的 */
    public interface Listener {
        void onList(List<Alert> list);
        void onNew(Alert a);
        /** connecting 连着呢 / live 已连上 / down 断了 */
        void onState(String state);
    }

    private final List<Alert> list = new ArrayList<>();               // 新的在前
    private final Map<Long, Alert> byId = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile String state = "connecting";
    private volatile boolean started = false;
    private Context app;

    /** 当前这条 SSE 连接，restart 时用来掐断 */
    private volatile HttpURLConnection live;
    /** 连接代次：每次切换/重连 +1，老线程看到不一致就自己退出 */
    private volatile int gen = 0;

    // ==========================================================================
    //  对外
    // ==========================================================================

    public void addListener(Listener l) {
        if (l == null) return;
        listeners.add(l);
        l.onList(snapshot());
        l.onState(state);
    }

    public void removeListener(Listener l) { listeners.remove(l); }

    public String state() { return state; }

    public synchronized List<Alert> snapshot() { return new ArrayList<>(list); }

    /** 配对过就带上凭证；云端据此只返回这一位老人的数据 */
    private static void sign(HttpURLConnection c) {
        String t = Conf.get().childToken();
        if (t.length() > 0) c.setRequestProperty("X-Yd-Child", t);
    }

    /**
     * 用 6 位配对码换凭证（在后台线程调用）。
     * @return null 表示成功；否则是给用户看的失败原因
     */
    public static String pair(String code) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(Conf.get().server() + "/api/pair/claim").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject o = new JSONObject();
            o.put("code", code.trim());
            c.getOutputStream().write(o.toString().getBytes("UTF-8"));
            int st = c.getResponseCode();
            java.io.InputStream in = st < 400 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject res = new JSONObject(sb.toString());
            if (!res.optBoolean("ok")) return res.optString("msg", "配对失败，请重试");
            String eid = res.optString("elderId", "");
            String tk = res.optString("token", "");
            if (eid.length() == 0 || tk.length() == 0) return "配对信息不完整，请重试";
            // 【2026-11-11 改：追加而不是覆盖】
            //   原来这里是 elderId(...) + childToken(...) 两个覆盖写，配第二位老人
            //   会把第一位整个顶掉，而子女端偏偏是最常要同时看两位老人的地方。
            //   云端每次 claim 都发一个新 token 且只对应一位老人，所以只要把
            //   多个 token 一起存住就够了，云端不用动。
            lastPairElderId = eid;
            lastPairIsNew = Conf.get().upsert(eid, tk);
            return null;
        } catch (Exception e) {
            return "无法连接服务器，请检查网络或服务器地址";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 重连：换了一位老人、用户点「重试」、或者改完服务器地址。
     *
     * 【2026-11-11 修：重连必须掐断老连接】
     *   原来这里只是把 started 置 false 再起一条新线程。
     *   但老线程这时正阻塞在 readLine() 上，没人关它 —— 它永远不会自己退出，
     *   于是同时存在两条 SSE：一条订阅上一位老人、一条订阅这一位。
     *   切到妈妈之后，另一位老人的预警照样会跳进妈妈的列表里。
     *   加了代次 generation：restart 时 gen++，老线程看到代次变了就退出；
     *   并且主动 disconnect 老连接，把它从 readLine 里叫醒。
     */
    public void restart() {
        final Context c = app;
        if (c == null) return;
        gen++;
        started = false;
        closeLive();
        setState("connecting");
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                synchronized (Hub.class) {
                    list.clear();
                    byId.clear();
                }
                start(c);
            }
        }).start();
    }

    public void start(Context c) {
        app = c.getApplicationContext();
        Conf.get().init(app);
        synchronized (Hub.class) {
            if (started) return;
            started = true;
        }
        final int my = gen;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(my); }
        }, "yindun-sse");
        t.setDaemon(true);
        t.start();
    }

    /** 掐断当前这条 SSE，让阻塞在 readLine 上的线程立刻抛出来 */
    private void closeLive() {
        HttpURLConnection c = live;
        live = null;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    // ==========================================================================
    //  连接循环
    // ==========================================================================

    private void loop(int my) {
        while (my == gen) {           // 代次变了说明用户切换/重连了，这条线程该退场
            try {
                setState("connecting");
                // 每次（重）连都先拉一次历史：
                //   App 刚打开时列表是空的，只靠增量推送会漏掉之前发生的事。
                //   已经有的按 id 去重，不会重复。
                loadHistory();
                if (my != gen) break;
                stream(my);               // 正常情况下这里不会返回
            } catch (Throwable e) {
                Log.w(TAG, "连接中断：" + e);
            }
            if (my != gen) break;
            setState("down");
            sleep(RECONNECT_MS);
        }
        Log.i(TAG, "订阅线程退出（代次 " + my + " → " + gen + "）");
    }

    /** 订阅实时流，阻塞读取，直到连接断掉 */
    private void stream(int my) throws Exception {
        String url = Conf.get().server() + "/api/stream?elderId="
                + URLEncoder.encode(Conf.get().elderId(), "UTF-8");
        Log.i(TAG, "订阅 " + url);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Accept", "text/event-stream");
        sign(c);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.connect();
        live = c;                    // 记下来，restart 时要把这条掐掉

        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        Log.i(TAG, "已连上实时流");
        setState("live");

        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder buf = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (my != gen) break;                     // 已经切走了，这条连接上的数据一律不算
            if (line.length() == 0) {                 // 空行 = 一帧结束
                if (buf.length() > 0) {
                    onFrame(buf.toString());
                    buf.setLength(0);
                }
            } else if (line.startsWith(":")) {
                // 心跳注释（": ping"），不动它，只用来证明连接还活着
            } else if (line.startsWith("data:")) {
                buf.append(line.substring(5).trim());
            }
        }
        r.close();
        c.disconnect();
        if (live == c) live = null;
    }

    private void onFrame(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String type = o.optString("type", "");

            if ("alert".equals(type)) {
                Alert a = new Alert();
                a.id = o.optLong("alertId", 0);
                a.level = o.optString("level", "watch");
                a.title = str(o, "title");
                a.body = str(o, "body");
                a.why = str(o, "why");
                a.summary = str(o, "summary");
                a.channel = o.optString("channel", "push");
                // 【不用服务端给的 time】那是服务端按它自己时区格式化好的 HH:mm，
                // 服务器在 UTC、手机在北京，就会显示成差 8 小时。
                // 这条推送是刚刚到的，用手机本地时间就是事发时间，最准也最不会错。
                a.time = now();
                a.ts = System.currentTimeMillis();

                boolean isNew = put(a);
                Log.i(TAG, "收到推送 #" + a.id + " " + a.level + " " + a.title);
                if (isNew) {
                    List<Alert> snap = snapshot();
                    for (Listener l : listeners) l.onList(snap);   // 先让列表更新
                    for (Listener l : listeners) l.onNew(a);       // 再通知（响铃）
                }
            } else if ("summary".equals(type)) {
                // 大模型的人话摘要晚几秒才回来，回来后就地补进那条卡片
                long id = o.optLong("alertId", 0);
                Alert a;
                synchronized (Hub.class) { a = byId.get(id); }
                if (a != null) {
                    a.summary = o.optString("summary", "");
                    List<Alert> snap = snapshot();
                    for (Listener l : listeners) l.onList(snap);
                }
            }
            // 其他 type（drill / stats 之类）子女端 App 不关心，直接忽略
        } catch (Exception e) {
            Log.w(TAG, "帧解析失败：" + json, e);
        }
    }

    /** 拉最近 20 条历史 */
    private void loadHistory() {
        try {
            String url = Conf.get().server() + "/api/alerts?limit=20&elderId="
                    + URLEncoder.encode(Conf.get().elderId(), "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            sign(c);
            c.connect();
            if (c.getResponseCode() != 200) { c.disconnect(); return; }

            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            c.disconnect();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("list");
            if (arr == null) return;

            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Alert a = new Alert();
                a.id = o.optLong("id", 0);
                a.level = o.optString("level", "watch");
                a.title = str(o, "title");
                a.body = str(o, "body");
                a.why = str(o, "why");
                a.summary = str(o, "summary");
                a.channel = o.optString("channel", "push");
                a.time = hhmm(str(o, "created_at"));
                if (put(a)) changed = true;
            }
            if (changed) {
                List<Alert> snap = snapshot();
                for (Listener l : listeners) l.onList(snap);
            }
            Log.i(TAG, "历史回填 " + arr.length() + " 条");
        } catch (Exception e) {
            Log.w(TAG, "历史加载失败：" + e);
        }
    }

    /** 放进时间线。返回 true 表示是新的一条 */
    private boolean put(Alert a) {
        synchronized (Hub.class) {
            if (a.id > 0 && byId.containsKey(a.id)) return false;
            if (a.id > 0) byId.put(a.id, a);
            // 按 id 从大到小插，保证顺序稳定（服务端 id 自增）
            int at = 0;
            while (at < list.size() && list.get(at).id > a.id) at++;
            list.add(at, a);
            // 演示时一个下午能堆几十条，截断免得越滑越长
            while (list.size() > 60) {
                Alert last = list.remove(list.size() - 1);
                if (last.id > 0) byId.remove(last.id);
            }
            return true;
        }
    }

    private void setState(String s) {
        if (s.equals(state)) return;
        state = s;
        for (Listener l : listeners) l.onState(s);
    }

    // ==========================================================================
    //  小工具
    // ==========================================================================

    private static String now() {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
    }

    /**
     * 服务端 created_at 有两种可能形状：
     *   "2026-11-09T06:34:00.000Z"   带 T 带 Z  —— 明确是 UTC
     *   "2026-11-09 06:34:00"        不带 T     —— 实测也是 UTC 墙上时间
     * 两种都得 +8 转成本地，否则时间线整体差 8 小时（第一版就是这么错的：
     * 下午 14:34 的提醒显示成 06:34）。
     */
    private static String hhmm(String s) {
        if (s == null || s.length() < 16) return "";
        String t = s.trim();
        try {
            Date d;
            if (t.indexOf('T') > 0) {
                String v = t.replace("Z", "");
                String fm = v.contains(".") ? "yyyy-MM-dd'T'HH:mm:ss.SSS" : "yyyy-MM-dd'T'HH:mm:ss";
                SimpleDateFormat in = new SimpleDateFormat(fm, Locale.US);
                in.setTimeZone(TimeZone.getTimeZone("UTC"));
                d = in.parse(v);
            } else {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                in.setTimeZone(TimeZone.getTimeZone("UTC"));
                d = in.parse(t.length() > 19 ? t.substring(0, 19) : t);
            }
            SimpleDateFormat out = new SimpleDateFormat("HH:mm", Locale.CHINA);
            out.setTimeZone(TimeZone.getDefault());
            return out.format(d);
        } catch (Exception e) {
            return t.length() >= 16 ? t.substring(11, 16) : "";
        }
    }

    /** JSON 里的 null 会被 optString 变成字符串 "null"，界面上就真的显示 null */
    private static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        String v = o.optString(key, "");
        return "null".equals(v) ? "" : v;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
