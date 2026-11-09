package com.yindun.guard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity —— App 的主界面（其实只是个壳）
 * ============================================================
 * 【它干四件事】
 *   1. 造一个全屏 WebView，把 index.html 装进去（界面已经写好了，一行不用重画）
 *   2. 向系统要权限
 *   3. 开一扇门（SilverBridge），让网页能反过来叫原生干活
 *   4. 拉起守护服务（GuardService），让监听活过一晚上
 *
 * 【为什么界面用 WebView 不用原生画】
 * 因为界面（老人端/子女端/红屏/绿屏）已经用 HTML 做完了，而且能随时改。
 * 套进 WebView，界面这块一行都不用重写 —— 这是省下的最大一块时间。
 *
 * 用户看不出区别：装进去是一个真正的 App，桌面有图标，能收短信，能弹窗。
 *
 * 【它不干的事】
 * 收短信在 SmsReceiver 里，看通话在 CallWatcher 里，判定在 Judge 里。
 * 界面只负责"显示"。职责分清楚，出问题才知道去哪找。
 * ============================================================
 */
public class MainActivity extends Activity {

    private static final String TAG = "YinDun";
    private WebView web;

    /**
     * 运行时要申请的"危险权限"。
     * 写在清单文件里不够，必须运行起来当面问用户要。
     */
    private static final String[] NEED = {
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE      // 阻断级自动拨给子女要用
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Stats.init(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // ---------- 1. 造 WebView ----------
        web = new WebView(this);

        // 【2026-11-09 修 ANR：模拟器的 GPU 是坏的，必须让 WebView 走软件渲染】
        //
        // 现象：银盾切到后台（微信在前台）约 5 秒，系统弹"银盾 isn't responding"。
        // 抓 ANR 堆栈，主线程卡在这里：
        //
        //   ThreadedRenderer.draw
        //     -> HardwareRenderer.syncAndDrawFrame
        //       -> DrawFrameTask::postAndWait
        //         -> pthread_cond_wait      ← 死等渲染线程画完一帧
        //
        // 根因在 logcat 里一直刷的那句：
        //   E chromium: eglChooseConfig failed with error EGL_SUCCESS
        //
        // 这个模拟器没有可用的 GPU，硬件渲染线程画不出帧，
        // 主线程就在"等这一帧"上被挂住 5 秒 —— 跟判定逻辑一点关系都没有。
        // （实测：只发一条 watch 级消息、连浮条都没弹，照样 ANR。）
        //
        // 软件渲染没有渲染线程这一层，主线程自己画完就往下走，绕开这条死路。
        // 页面本身很轻（无滚动、无动画），软件渲染完全够用。
        // 真机上硬件渲染是好的，但因为页面简单，统一走软件渲染也不会明显变慢，
        // 所以不按机型分支，少一处"只在模拟器成立"的代码。
        web.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);

        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);      // 网页要跑脚本
        s.setDomStorageEnabled(true);      // 让网页能存本地状态
        s.setAllowFileAccess(true);        // 读 assets 里的文件
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // 网页里的链接不要跳到系统浏览器，就待在我们的框里
        web.setWebViewClient(new WebViewClient());

        // ---------- 2. 开门给网页 ----------
        web.addJavascriptInterface(new Bridge(), "SilverBridge");

        // ---------- 3. 装界面 ----------
        loadRole();

        // ---------- 4. 把管子接到事件总线 ----------
        EventBus.attach(web);

        // ---------- 5. 要权限 ----------
        askPermissions();

        // ---------- 6. 拉起守护服务 ----------
        startGuard();

        // ---------- 7. 第一次进来，问一次华为云模型怎么配 ----------
        // 只问一次，之后可以在网页里改。跳过也行，跳过不影响本地拦截。
        askModelKeyIfNeeded();

        // 如果是从 Shield 降级跳进来的（没悬浮窗权限），直接显示红屏
        if (getIntent() != null && getIntent().getBooleanExtra("force_shield", false)) {
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    web.evaluateJavascript("window.onAlert({level:'block',why:'通话中做了动钱的操作'});", null);
                }
            }, 800);
        }
    }

    /** 按"这台手机是老人用还是子女用"加载不同的界面 */
    private void loadRole() {
        String role = getSharedPreferences("yindun", MODE_PRIVATE)
                .getString("role", "elder");
        // 【2026-11-09 改】老人端换成了极简守护页。
        // 原来那个 index.html 里摆着 4 条写好的短信剧本，一打开全在屏幕上，
        // 演的其实是"银盾会判短信"，不是"银盾在守着我"。
        // 真实的拦截发生在微信、电话、银行 App 上，靠的是屏幕顶上的浮条。
        String page = "elder".equals(role) ? "guard.html" : "index.html";
        String url = "file:///android_asset/" + page + "?role=" + role;
        Log.i(TAG, "加载界面 " + url);
        web.loadUrl(url);
    }

    // ==========================================================
    //  权限
    // ==========================================================
    private void askPermissions() {
        List<String> missing = new ArrayList<String>();
        for (String p : NEED) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        // 安卓 13 起发通知也要单独申请（不申请就弹不出预警）
        if (Build.VERSION.SDK_INT >= 33) {
            String n = "android.permission.POST_NOTIFICATIONS";
            if (checkSelfPermission(n) != PackageManager.PERMISSION_GRANTED) missing.add(n);
        }
        if (missing.isEmpty()) {
            toast("权限已全部授予，银盾防护已开启");
            return;
        }
        requestPermissions(missing.toArray(new String[0]), 1001);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != 1001) return;
        int denied = 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) denied++;
        if (denied == 0) {
            toast("权限已全部授予，银盾防护已开启");
        } else {
            toast("有 " + denied + " 项权限未授予，部分防护功能将无法使用");
        }
    }

    /** 带老人去开读录音要的权限：安卓 11+ 是"所有文件访问"，以下是存储权限 */
    private void askFileAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                toast("请开启「允许访问所有文件」，用于读取系统通话录音");
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1002);
            }
        } catch (Exception e) {
            toast("无法打开权限设置页，请前往 设置 → 应用 → 银盾 → 权限 手动开启存储权限");
        }
    }

    /** 让首页重新读一次录音复盘的状态 */
    private void refreshRecPage() {
        if (web != null) web.evaluateJavascript("try{refreshRec()}catch(e){}", null);
    }

    // ==========================================================
    //  守护服务
    // ==========================================================
    private void startGuard() {
        try {
            Intent i = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Log.e(TAG, "启动守护服务失败", e);
        }
    }

    // ==========================================================
    //  SilverBridge —— 网页叫原生干活的唯一一扇门
    // ==========================================================
    /**
     * 安全性说明：
     * addJavascriptInterface 在安卓 4.2 以下有漏洞，我们最低支持安卓 7，不受影响。
     * 门里只放必要的方法，绝不放"执行任意命令"这种。
     */
    private class Bridge {

        /** 网页问：权限齐了吗？用来决定界面显示"守护中"还是"未就绪" */
        @JavascriptInterface
        public boolean ready() {
            for (String p : NEED) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
            }
            return true;
        }

        /** 网页问：悬浮窗权限开了吗？没开就盖不住屏，这是阻断级的前提 */
        @JavascriptInterface
        public boolean canShield() {
            return Shield.canOverlay(MainActivity.this);
        }

        /** 盖住整屏（阻断级） */
        @JavascriptInterface
        public void shield(String title) {
            Log.i(TAG, "网页要求盖屏：" + title);
            // 振动由 Shield.show 内部负责，这里不再重复调用，免得叠两遍
            Shield.show(MainActivity.this, title, Guard.get(MainActivity.this).callNumber);
        }

        /** 撤掉盖屏（子女接通后） */
        @JavascriptInterface
        public void shieldHide() {
            Shield.hide(MainActivity.this);
        }

        /**
         * 模拟一次陌生来电的"熟人询问"（只给演示用，真实场景由 CallWatcher 触发）。
         *
         * 保留这个入口的理由：真实来电需要另一台手机配合，
         * 验收和录屏时用它可以省掉"喂，你打我一下"这一步。
         */
        @JavascriptInterface
        public void acquaintAsk(String number) {
            String num = (number == null || number.trim().length() == 0)
                    ? "+12025550137" : number.trim();
            Log.i(TAG, "演示：模拟熟人询问 " + num);
            Acquaint.askIfNeeded(MainActivity.this, num, NumberInfo.STRANGER);
        }

        /** 撤掉熟人询问卡 */
        @JavascriptInterface
        public void acquaintHide() {
            Acquaint.dismiss();
        }

        /** 播女儿的声音：stop / moving / code */
        @JavascriptInterface
        public void speak(String key) {
            Speak.play(MainActivity.this, key);
        }

        /** 打开反诈演练（网页里点"定时演练"按钮时调这里，进的是原生页） */
        @JavascriptInterface
        public void openDrill() {
            try {
                Intent i = new Intent(MainActivity.this, DrillActivity.class);
                startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "打开演练页失败：" + e.getMessage());
            }
        }

        /**
         * 打开上报页（网页里点"上传诈骗信息"时调这里，进的是原生页）。
         * 网页端只是入口，真正的读短信、选截图、看判定结果都在原生页里做，
         * 老人不用在小屏幕上跟一个网页较劲。
         */
        @JavascriptInterface
        public void openReport() {
            try {
                Intent i = new Intent(MainActivity.this, ReportActivity.class);
                startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "打开上报页失败：" + e.getMessage());
            }
        }

        /** 网页问：演练提醒排上了吗（调试用） */
        @JavascriptInterface
        public long drillNextAt() {
            return DrillAlarm.nextTriggerAt();
        }

        /**
         * 网页里点「转发」—— 真的把这条短信传到云端，等云端判完再回话。
         *
         * 【为什么必须经过原生，不能在网页里直接 fetch】
         * index.html 是 file:///android_asset 里的页面，跨域直接被挡，
         * 而且网页里没法给"没连上"一个体面的交代。走原生既能发出去，
         * 也能把服务端的判定原样带回页面 —— 手机上和子女端看到的必须是同一个结论。
         *
         * 【回话方式】
         * 判完调 window.onReportResult(idx, jsonText)。
         * jsonText 为 null 表示没连上，页面会把按钮还原，让她再点一次。
         */
        @JavascriptInterface
        public void reportSms(final int idx, final String number, final String text) {
            if (text == null || text.trim().length() == 0) return;
            Log.i(TAG, "网页要求上报第 " + idx + " 条短信");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String body = null;
                    try {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("elderId", "demo");
                        o.put("reporter", "elder");   // 这条是老人自己转发的
                        o.put("number", number == null ? "" : number);
                        o.put("text", text);
                        body = Notifier.postJsonSync(MainActivity.this, "/api/report", o, 20000);
                    } catch (Exception e) {
                        Log.w(TAG, "上报组包失败：" + e.getMessage());
                    }
                    final String json = body;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String arg = (json == null) ? "null" : org.json.JSONObject.quote(json);
                            try {
                                web.evaluateJavascript("window.onReportResult&&window.onReportResult("
                                        + idx + "," + arg + ")", null);
                            } catch (Exception e) {
                                Log.w(TAG, "回话给网页失败：" + e.getMessage());
                            }
                        }
                    });
                }
            }).start();
        }

        /** 真拨出去 */
        @JavascriptInterface
        public void dial(String number) {
            try {
                Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
                startActivity(i);
            } catch (Exception e) {
                toast("拨号失败：" + e.getMessage());
            }
        }

        /** 拨给子女（号码在绑定阶段填好） */
        @JavascriptInterface
        public void callChild() {
            Notifier.callChild(MainActivity.this);
        }

        /** 给子女发预警（通知 + 推后端） */
        @JavascriptInterface
        public void alert(String level, String why) {
            Notifier.alertChild(MainActivity.this, level, "银盾预警", why);
        }

        /** 跳到系统设置页。name: sms/overlay/battery/usage/notify/app */
        @JavascriptInterface
        public void openSetting(String name) {
            try {
                Intent i;
                if ("overlay".equals(name)) {
                    i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                } else if ("usage".equals(name)) {
                    i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                } else if ("battery".equals(name)) {
                    i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                } else if ("notify".equals(name)) {
                    i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                } else {
                    // sms / autostart 都跳到应用详情页（各厂商的自启动页没法通用）
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast("无法打开该设置页，请在系统设置中手动开启");
            }
        }

        /** 首页数字：守护天数、今天检查了多少、最近一次提醒（JSON） */
        @JavascriptInterface
        public String stats() {
            return Stats.json(MainActivity.this);
        }

        /** 通话录音复盘的状态：on=在工作 / noperm=开了但没文件权限 / off=没开 */
        @JavascriptInterface
        public String recState() {
            if (!CallRecord.enabled(MainActivity.this)) return "off";
            return CallRecord.hasAccess(MainActivity.this) ? "on" : "noperm";
        }

        /** 首页点「通话录音复盘」：没开 → 同意说明；缺权限 → 去授权；已开 → 问要不要关 */
        @JavascriptInterface
        public void recToggle() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String st = recState();
                    if ("noperm".equals(st)) {
                        askFileAccess();
                    } else if ("on".equals(st)) {
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("关闭通话复盘？")
                                .setMessage("关闭后，陌生来电挂断后将不再分析通话录音。")
                                .setPositiveButton("关闭", new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        CallRecord.setEnabled(MainActivity.this, false);
                                        refreshRecPage();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    } else {
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("开启通话复盘")
                                .setMessage("· 请先在系统电话中开启「自动通话录音」\n"
                                        + "· 仅分析不在通讯录中的陌生号码\n"
                                        + "· 挂断后，录音将上传至银盾云端进行诈骗识别\n"
                                        + "· 云端不保存录音及完整对话内容\n\n"
                                        + "您可随时在此关闭。")
                                .setPositiveButton("同意并开启", new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        CallRecord.setEnabled(MainActivity.this, true);
                                        if (CallRecord.hasAccess(MainActivity.this)) toast("通话复盘已开启");
                                        else askFileAccess();
                                        refreshRecPage();
                                    }
                                })
                                .setNegativeButton("暂不开启", null)
                                .show();
                    }
                }
            });
        }

        /** 子女号码：读 / 写 */
        @JavascriptInterface
        public String childNumber() {
            return Notifier.getChildNumber(MainActivity.this);
        }

        @JavascriptInterface
        public void setChildNumber(String num) {
            Notifier.setChildNumber(MainActivity.this, num);
        }

        /** 后端地址：读 / 写 */
        @JavascriptInterface
        public String backend() {
            return Notifier.getBackend(MainActivity.this);
        }

        /**
         * 演示倍速。
         *
         * 【为什么需要这个开关】
         * 判级门槛是按真实产品定的：陌生号码通话满 5 分钟报关注、满 8 分钟报警告。
         * 演示现场没人会干等 8 分钟，所以给一个"真实 1 秒记作 N 秒"的倍速。
         * 它只放大判级用的时长，真实计时、真实通话都不受影响；
         * 演示结束把它设回 1，就是线上产品的行为。
         */
        @JavascriptInterface
        public int demoSpeed() {
            return Guard.get(MainActivity.this).demoSpeed();
        }

        @JavascriptInterface
        public void setDemoSpeed(int n) {
            Guard.setDemoSpeed(MainActivity.this, n);
        }

        @JavascriptInterface
        public void setBackend(String url) {
            Notifier.setBackend(MainActivity.this, url);
        }

        /** 这台手机是老人机还是子女机 */
        @JavascriptInterface
        public void setRole(final String role) {
            getSharedPreferences("yindun", MODE_PRIVATE).edit().putString("role", role).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    loadRole();
                }
            });
        }

        /**
         * 诊断信息。答辩现场如果哪里不对，把这个念出来就知道问题在哪。
         * 规则有没有加载、加载失败的原因、各权限状态，全在里面。
         */
        @JavascriptInterface
        public String diag() {
            Judge j = Judge.get(MainActivity.this);
            StringBuilder sb = new StringBuilder();
            sb.append("规则加载=").append(j.loaded ? "成功" : "失败：" + j.loadError).append("\n");
            sb.append("悬浮窗=").append(Shield.canOverlay(MainActivity.this) ? "有" : "无").append("\n");
            sb.append("子女号码=").append(Notifier.childBound(MainActivity.this)
                    ? Notifier.getChildNumber(MainActivity.this)
                    : "未绑定（阻断时拨演示号 " + Notifier.DEMO_CHILD_NUMBER + "）").append("\n");
            sb.append("演示倍速=×").append(Guard.get(MainActivity.this).demoSpeed()).append("\n");
            sb.append("后端=").append(Notifier.getBackend(MainActivity.this)).append("\n");
            sb.append("录音复盘=").append(recState()).append("\n");
            for (String p : NEED) {
                sb.append(p.replace("android.permission.", ""))
                  .append("=")
                  .append(checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED ? "有" : "无")
                  .append("\n");
            }
            return sb.toString();
        }

        @JavascriptInterface
        public void toast(String msg) {
            MainActivity.this.toast(msg);
        }

        // ======================================================
        //  华为云盘古大模型（创新赛道要求的那条链路）
        // ======================================================

        /**
         * 试一下模型通不通。这是拿到账号后第一个该点的按钮。
         *
         * 【为什么要有这个方法】
         * 因为现在还不知道账号里模型服务开没开通。
         * 点一下，成功了会告诉你模型回了什么；失败了会告诉你
         * 是 Key 不对、地址不对、还是没网 —— 直接告诉你该去控制台点哪里。
         *
         * 结果通过 window.onNativeEvent({type:'ai',kind:'ping',...}) 回来。
         */
        @JavascriptInterface
        public void testModel() {
            Pangu.ping(MainActivity.this, new Pangu.Callback() {
                @Override
                public void ok(String text) {
                    EventBus.pushAI("ping", text, true);
                    toast(text);
                }
                @Override
                public void fail(String reason) {
                    EventBus.pushAI("ping", reason, false);
                    toast(reason);
                }
            });
        }

        /** 网页问：模型配好了没 / 当前配的是什么 */
        @JavascriptInterface
        public boolean modelReady() {
            return Pangu.configured(MainActivity.this);
        }

        @JavascriptInterface
        public String modelInfo() {
            Context c = MainActivity.this;
            return "地址=" + Pangu.getUrl(c) + "\n模型=" + Pangu.getModel(c)
                    + "\nKey=" + (Pangu.configured(c)
                        ? Pangu.getKey(c).substring(0, 6) + "…" + Pangu.getKey(c)
                            .substring(Math.max(0, Pangu.getKey(c).length() - 4))
                        : "未填");
        }

        /** 网页填好 Key 之后调这个存进去。下次进来不用再填 */
        @JavascriptInterface
        public void setModelConfig(String url, String key, String model) {
            Pangu.setConfig(MainActivity.this, url, key, model);
            toast("模型配置已保存");
        }

        /**
         * 让模型解释"现在正在发生什么"，给子女端补一句人话。
         * 结果通过 {type:'ai', kind:'explain'} 回来。
         */
        @JavascriptInterface
        public void explainNow() {
            Guard g = Guard.get(MainActivity.this);
            String ev = Pangu.eventsOf(g.st, null);
            Pangu.explain(MainActivity.this, ev, new Pangu.Callback() {
                @Override
                public void ok(String text) {
                    EventBus.pushAI("explain", text, true);
                }
                @Override
                public void fail(String reason) {
                    EventBus.pushAI("explain", reason, false);
                }
            });
        }

        /**
         * 手动复审一条短信（调试用）。
         * 现场答辩如果评委想当场验一条短信，用这个，不用真发短信。
         */
        @JavascriptInterface
        public void reviewSms(String body, String who, int score) {
            Pangu.reviewSms(MainActivity.this, body, who, score, new Pangu.Callback() {
                @Override
                public void ok(String text) {
                    EventBus.pushAI("review", text, true);
                }
                @Override
                public void fail(String reason) {
                    EventBus.pushAI("review", reason, false);
                }
            });
        }
    }

    // ==========================================================
    //  华为云模型配置对话框
    // ==========================================================
    /**
     * 第一次启动时弹一次，让用户填模型地址和 Key。
     *
     * 【为什么用原生对话框，不放在网页里】
     * 因为网页那块是 C 线在改，现在还没做配置页。
     * 而且填 Key 是一次性的事，用原生弹窗最直接，
     * 不依赖界面那边的进度，装完就能测通。
     *
     * 【为什么可以跳过】
     * 因为本地拦截完全不依赖模型。填不填，盖屏都照常工作。
     * 强迫填反而会让人烦躁，然后卸载。
     */
    private void askModelKeyIfNeeded() {
        if (Pangu.configured(this)) return;
        if (!getSharedPreferences("yindun", MODE_PRIVATE).getBoolean("asked_model", false)) {
            getSharedPreferences("yindun", MODE_PRIVATE).edit()
                    .putBoolean("asked_model", true).apply();
        } else {
            return;   // 问过了，不再烦
        }

        final float dp = getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, (int) (8 * dp), pad, 0);

        TextView tip = new TextView(this);
        tip.setText("银盾的实时拦截全部在本机完成，不需要联网。\n"
                + "下面这项只用于两件事：给拿不准的短信做二次判断、把预警翻成一句人话。\n"
                + "不填也能正常用，随时可以再配。");
        tip.setTextSize(13);
        tip.setPadding(0, 0, 0, (int) (14 * dp));
        box.addView(tip);

        final EditText edUrl = new EditText(this);
        edUrl.setHint("接口地址");
        edUrl.setText(Pangu.getUrl(this));
        edUrl.setTextSize(12);
        box.addView(edUrl);

        final EditText edKey = new EditText(this);
        edKey.setHint("API Key（华为云 ModelArts Studio → 应用接入）");
        edKey.setTextSize(12);
        box.addView(edKey);

        final EditText edModel = new EditText(this);
        edModel.setHint("模型名");
        edModel.setText(Pangu.getModel(this));
        edModel.setTextSize(12);
        box.addView(edModel);

        new android.app.AlertDialog.Builder(this)
                .setTitle("配置华为云盘古大模型")
                .setView(box)
                .setPositiveButton("保存并测试", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Pangu.setConfig(MainActivity.this,
                                edUrl.getText().toString(),
                                edKey.getText().toString(),
                                edModel.getText().toString());
                        // 立刻验一次，通了当场就知道
                        Pangu.ping(MainActivity.this, new Pangu.Callback() {
                            @Override public void ok(String text) {
                                toast("✓ " + text);
                                EventBus.pushAI("ping", text, true);
                            }
                            @Override public void fail(String reason) {
                                toast("✗ " + reason);
                                EventBus.pushAI("ping", reason, false);
                            }
                        });
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    /** 老人按返回键不要退出 App（退了守护就断了） */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true);   // 退到后台，不销毁
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到主界面都把演练提醒重排一遍。
        // 国产 ROM 会清后台闹钟，"设一次管一辈子"在这里不成立。
        DrillAlarm.schedule(this);
        // 从"所有文件访问"设置页回来，首页的录音复盘状态要跟着变
        refreshRecPage();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
