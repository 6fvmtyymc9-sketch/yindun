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

    /** 配对凭证：用老人端给的 6 位配对码换来，只能查看这一位老人的预警 */
    public String childToken() { return sp.getString("childToken", ""); }

    public void childToken(String v) { sp.edit().putString("childToken", v == null ? "" : v).apply(); }

    /** 有没有完成配对（没配对时看的是公开演示编号 demo） */
    public boolean paired() { return childToken().length() > 0; }

    /** 怎么称呼老人（妈妈 / 爸爸 / 奶奶…），界面上所有"妈妈"都用它 */
    public String elderName() {
        String v = sp.getString("elderName", "妈妈");
        return v == null || v.trim().length() == 0 ? "妈妈" : v.trim();
    }

    public void elderName(String v) { sp.edit().putString("elderName", v == null ? "" : v.trim()).apply(); }

    /** 老人的手机号，"打给妈妈"按钮用。没填就空着，按钮会提示去设置 */
    public String elderPhone() { return sp.getString("elderPhone", "").trim(); }

    public void elderPhone(String v) { sp.edit().putString("elderPhone", v == null ? "" : v.trim()).apply(); }

    /** 子女点过"已处理"的提醒（只存在本机，不影响老人端） */
    public boolean handled(long id) { return sp.getBoolean("handled_" + id, false); }

    public void handled(long id, boolean v) { sp.edit().putBoolean("handled_" + id, v).apply(); }

    /** 新提醒到的时候要不要响一声。答辩现场可以关，避免录音里全是铃声 */
    public boolean sound() { return sp.getBoolean("sound", true); }

    public void sound(boolean v) { sp.edit().putBoolean("sound", v).apply(); }
}
