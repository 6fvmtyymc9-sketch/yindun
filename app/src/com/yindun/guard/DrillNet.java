package com.yindun.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * ============================================================================
 *  DrillNet —— 演练功能访问云端的唯一出口
 * ----------------------------------------------------------------------------
 *  为什么不在 App 里直连 MaaS：
 *    1. API Key 存在手机上，谁反编译都能拿走，这是安全事故；
 *    2. 演练记录要落 RDS，子女端才能看到"我妈这周练了多少分"；
 *    3. 台词、判分、题库都在云端，改一次不用发新版本。
 *
 *  地址从 SharedPreferences 读，首次进演练页会让你填一次：
 *    同一 WiFi：http://192.168.x.x:8080
 *    上云之后：http://你的ECS公网IP:8080
 *
 *  任何失败都不抛给界面：调用方拿到 null 就切到内置剧本，
 *  保证"没网也能演练"——这是现场演示的底线。
 * ============================================================================
 */
class DrillNet {

    private static final String TAG = "DrillNet";
    private static final String SP = "yindun_drill";
    private static final String K_BASE = "cloud_base";

    /** 已部署的云端地址。装完即可用，不必再手填 */
    static final String DEFAULT_BASE = "http://123.249.25.139:8080";

    /**
     * 演练 / 上报页单独填过地址就用它；没填过就跟预警上报用同一个地址（Notifier.getBackend）。
     * 原来没填时直接走 DEFAULT_BASE，结果预警推到了本机云端，演练却去连公网那台，
     * 演示时演练页一直显示"连不上云端"。
     */
    static String base(Context c) {
        String v = sp(c).getString(K_BASE, "");
        if (v == null || v.trim().length() < 8) return Notifier.getBackend(c);
        return v.trim();
    }

    static void setBase(Context c, String b) {
        sp(c).edit().putString(K_BASE, b == null ? "" : b.trim()).apply();
    }

    static boolean hasBase(Context c) {
        String b = base(c);
        return b != null && b.length() > 7;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** GET 一个接口，失败返回 null */
    static JSONObject get(Context c, String path) {
        return request(c, path, null, 8000);
    }

    /** POST 一个接口，失败返回 null */
    static JSONObject post(Context c, String path, JSONObject body) {
        return request(c, path, body, 25000);
    }

    private static JSONObject request(Context c, String path, JSONObject body, int timeout) {
        if (!hasBase(c)) return null;
        HttpURLConnection conn = null;
        try {
            String b = base(c);
            if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            URL u = new URL(b + path);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "YindunApp/" + Build.VERSION.SDK_INT);

            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = body.toString().getBytes(Charset.forName("UTF-8"));
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            } else {
                conn.setRequestMethod("GET");
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();

            String txt = new String(bo.toByteArray(), Charset.forName("UTF-8"));
            if (code < 200 || code >= 300) {
                Log.w(TAG, "HTTP " + code + " " + path + " → " + txt);
                return null;
            }
            return new JSONObject(txt);
        } catch (Exception e) {
            Log.w(TAG, "请求失败 " + path + "：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
