package com.yindun.child;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 子女端的配置：连哪台服务器、看哪位老人、要不要响铃。
 *
 * 【为什么不写死在代码里】
 * 服务器地址是真实 IP，写死在源码里就意味着以后仓库一旦公开，
 * 谁都能顺着地址摸到接口。所以真实地址由构建脚本在编译时注入
 * （ServerGen.java，不进仓库），这里只负责在运行时可改。
 */
public final class Conf {

    private static final String PREFS = "yindun_child";
    private static final Conf I = new Conf();

    private SharedPreferences sp;

    public static Conf get() { return I; }

    public void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    /** 服务器根地址，形如 http://1.2.3.4:8080，结尾不带斜杠 */
    public String server() {
        String s = sp.getString("server", "");
        if (s == null || s.length() == 0) s = ServerGen.BASE;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public void server(String v) { sp.edit().putString("server", v.trim()).apply(); }

    /** 看哪位老人。现在系统是单老人（elderId=demo），将来一对多就换成绑定码 */
    public String elderId() { return sp.getString("elderId", "demo"); }

    public void elderId(String v) {
        String t = v == null ? "" : v.trim();
        sp.edit().putString("elderId", t.length() == 0 ? "demo" : t).apply();
    }

    /** 新提醒到的时候要不要响一声。答辩现场可以关，避免录音里全是铃声 */
    public boolean sound() { return sp.getBoolean("sound", true); }

    public void sound(boolean v) { sp.edit().putBoolean("sound", v).apply(); }
}
