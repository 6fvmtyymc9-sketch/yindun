package com.yindun.guard;

import android.webkit.WebView;

import java.lang.ref.WeakReference;

/**
 * EventBus —— 原生世界通往网页世界的唯一一扇门
 * ============================================================
 * 【为什么需要它】
 * 短信广播（SmsReceiver）是系统直接叫起来的，它跟界面（MainActivity）
 * 压根不在一个地方，互相看不见。可短信必须送到界面上显示。
 *
 * 这个类就是中间那根管子：谁有事件，往 push() 里扔；界面上会自动亮。
 *
 * 【为什么用 WeakReference（弱引用）】
 * 如果这里直接存 WebView 的对象（强引用），那么用户退出 App 之后，
 * 这块内存也不会被回收 —— 因为 EventBus 是 static 的，它一直抓着 WebView。
 * 用 WeakReference 就是告诉 Java："抓着，但别人要回收你就松手。"
 * 这样既不会内存泄漏，退出后再进来也能接上。
 *
 * 【线程问题，必须知道】
 * 短信广播是在"广播线程"里执行的，而 WebView 只允许在"主线程"里碰。
 * 所以下面用 web.post(...) 把活儿扔回主线程。少了这一步，
 * 轻则报错，重则直接崩。
 * ============================================================
 */
public class EventBus {

    /** 当前活着的 WebView。可能为 null（App 还没打开或已退出）。 */
    private static WeakReference<WebView> webRef = null;

    /** 界面创建时调用一次，把这根管子接到网页上。 */
    public static void attach(WebView web) {
        webRef = new WeakReference<WebView>(web);
    }

    /**
     * 往网页推一个事件。
     *
     * @param json 已经拼好的 JSON 字符串，例如
     *             {"type":"sms","from":"+8613800138000","body":"...","stranger":true}
     *
     * 原理：网页里我们要实现一个 window.onNativeEvent(obj) 函数，
     *      这里就是隔着 WebView 去调用它。相当于原生世界敲了敲网页的门。
     */
    public static void push(final String json) {
        if (webRef == null) return;              // 管子还没接上，直接丢掉
        final WebView web = webRef.get();
        if (web == null) return;                 // 界面已经被回收了

        // 切回主线程再执行（WebView 的硬规矩）
        web.post(new Runnable() {
            @Override
            public void run() {
                // evaluateJavascript 是异步的，第二个参数是回调，这里用不到
                web.evaluateJavascript("window.onNativeEvent(" + json + ");", null);
            }
        });
    }

    /**
     * 推一张预警卡给子女端界面。
     *
     * 【它和 push() 的区别】
     * push() 推的是"发生了什么"（原始事件），给判据看板用。
     * 这里推的是"判出了什么"（结论），给子女端那张卡用。
     *
     * 【为什么要分两条通道】
     * 因为界面里这两块是分开的：判据看板演示用，红了要能追原因；
     * 子女端的卡片是要给她看的，只能有一句话，多了她就不看。
     */
    public static void pushAlert(final String level, final String why,
                                 final String stage, final int score) {
        if (webRef == null) return;
        final WebView web = webRef.get();
        if (web == null) return;

        final String js;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("level", level);
            o.put("why", why == null ? "" : why);
            o.put("stage", stage);
            o.put("score", score);
            o.put("time", new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date()));
            js = "window.onAlert(" + o.toString() + ");";
        } catch (Exception e) {
            return;
        }

        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    /**
     * 推一条"华为云模型"的结果给界面。
     *
     * 【为什么要单独一条通道】
     * 因为模型返回是异步的，可能比事件晚 1~3 秒。
     * 界面收到它的时候，那张卡已经在屏幕上了，这条只是"补一句"。
     * 单独一条通道，界面才能知道"这是 AI 后补的，不是新事件"。
     *
     * @param kind  ping / review / explain
     * @param text  模型说的话；失败时是失败原因
     * @param ok    成功还是失败
     */
    public static void pushAI(final String kind, final String text, final boolean ok) {
        if (webRef == null) return;
        final WebView web = webRef.get();
        if (web == null) return;

        final String js;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", "ai");
            o.put("kind", kind);
            o.put("ok", ok);
            o.put("text", text == null ? "" : text);
            js = "window.onNativeEvent(" + o.toString() + ");";
        } catch (Exception e) {
            return;
        }

        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }
}
