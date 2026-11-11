package com.yindun.child;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 子女端的配置：连哪台服务器、看哪位老人、要不要响铃。
 *
 * 【为什么不写死在代码里】
 * 服务器地址是真实 IP，写死在源码里就意味着以后仓库一旦公开，
 * 谁都能顺着地址摸到接口。所以真实地址由构建脚本在编译时注入
 * （ServerGen.java，不进仓库），这里只负责在运行时可改。
 *
 * 【为什么是"名单"而不是一个 elderId】（2026-11-11）
 * 原来这里只有单个 elderId + 单个 token，注释里写着"将来一对多就换成绑定码"。
 * 但真实情况是一个子女常有两位老人要照看（爸妈分开住、爷爷奶奶），
 * 单老人意味着他得装两个 App 或者来回重配。
 * 现在存一份名单 [{id,name,phone,token}] + 当前选中项；云端那边本来就是
 * 一条 token 只对应一位老人（/api/pair/claim 每次发一个新 token），
 * 所以这里只需要把"多个 token"管好，云端一行都不用改。
 *
 * 兼容：旧版本只写过 elderId / childToken / elderName / elderPhone 四个键，
 * 首次读取名单时会自动迁移成一条记录，用户无感。
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

    // ==========================================================================
    //  老人名单
    // ==========================================================================

    /** 一位被照看的老人。token 是配对时换来的凭证，只能看这一位 */
    public static final class Elder {
        public String id;
        public String name;
        public String phone;
        public String token;

        Elder(String id, String name, String phone, String token) {
            this.id = id;
            this.name = name;
            this.phone = phone;
            this.token = token;
        }

        /** 公开演示编号，没有凭证，看的是演示数据 */
        public boolean isDemo() { return "demo".equals(id); }

        /** 配对编号只显示后 4 位，全串太长、老人看不懂也不需要 */
        public String shortId() {
            if (id == null) return "";
            return id.length() <= 4 ? id : id.substring(id.length() - 4);
        }
    }

    /**
     * 全部老人，按添加顺序。永远至少有一条（没配过就是 demo）。
     *
     * 【为什么每次都从 JSON 重新解析】
     * 名单改动很少，一次解析是几十微秒；换来的是任何地方改了名单后，
     * 别处读到的立刻是最新的，不需要维护内存副本和失效逻辑。
     */
    public List<Elder> elders() {
        String raw = sp.getString("elders", "");
        if (raw == null || raw.length() == 0) {
            migrateLegacy();
            raw = sp.getString("elders", "");
        }
        List<Elder> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = str(o.optString("id", ""));
                if (id.length() == 0) continue;
                out.add(new Elder(id,
                        orDefault(o.optString("name", ""), "老人"),
                        str(o.optString("phone", "")),
                        str(o.optString("token", ""))));
            }
        } catch (Exception ignored) {
            // 名单坏了就当没配过，不要让子女端因为一条脏数据打不开
        }
        if (out.isEmpty()) out.add(new Elder("demo", "妈妈", "", ""));
        return out;
    }

    /** 当前正在看的那位 */
    public Elder current() {
        List<Elder> all = elders();
        String cid = sp.getString("currentElder", "");
        for (Elder e : all) {
            if (e.id.equals(cid)) return e;
        }
        return all.get(0);
    }

    public int elderCount() { return elders().size(); }

    /** 切到某位老人。只改选中项，不动凭证 */
    public boolean switchTo(String id) {
        List<Elder> all = elders();
        for (Elder e : all) {
            if (e.id.equals(id)) {
                save(all, id);
                return true;
            }
        }
        return false;
    }

    /**
     * 配对成功后登记一位老人，并把当前选中的切过去。
     *
     * @return true = 这是一位新老人；false = 原来就绑过，只是换了新凭证
     */
    public boolean upsert(String id, String token) {
        if (id == null || id.length() == 0) return false;
        List<Elder> all = elders();
        for (Elder e : all) {
            if (e.id.equals(id)) {
                e.token = str(token);
                save(all, id);
                return false;
            }
        }
        // 一直是"看演示数据"的状态，一旦真的配上对，就把那条占位的演示记录摘掉。
        // 否则面板里会一直躺着一条"演示数据"，子女会以为绑了两位老人。
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).isDemo() && all.get(i).token.length() == 0) all.remove(i);
        }
        // 新老人先给个临时称呼，配对完会立刻问子女怎么称呼
        String tmp = all.isEmpty() ? "妈妈" : "老人" + (all.size() + 1);
        all.add(new Elder(id, tmp, "", str(token)));
        save(all, id);
        return true;
    }

    /** 改称呼 */
    public void name(String id, String v) {
        List<Elder> all = elders();
        for (Elder e : all) {
            if (e.id.equals(id)) {
                e.name = orDefault(str(v), e.name);
                save(all, null);
                return;
            }
        }
    }

    /** 改手机号（"打给妈妈"按钮用） */
    public void phone(String id, String v) {
        List<Elder> all = elders();
        for (Elder e : all) {
            if (e.id.equals(id)) {
                e.phone = str(v);
                save(all, null);
                return;
            }
        }
    }

    /**
     * 解除与某位老人的绑定。
     *
     * @return false 表示这是最后一位，不能移除 —— 全删光会让界面没东西可看，
     *         而且用户多半是误触。要清空请用「恢复演示数据」。
     */
    public boolean remove(String id) {
        List<Elder> all = elders();
        if (all.size() <= 1) return false;
        String next = null;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        if (id.equals(current().id)) next = all.get(0).id;
        save(all, next);
        return true;
    }

    private void save(List<Elder> list, String currentId) {
        JSONArray a = new JSONArray();
        for (Elder e : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", e.id);
                o.put("name", e.name);
                o.put("phone", str(e.phone));
                o.put("token", str(e.token));
            } catch (Exception ignored) { }
            a.put(o);
        }
        SharedPreferences.Editor ed = sp.edit().putString("elders", a.toString());
        if (currentId != null) ed.putString("currentElder", currentId);
        ed.apply();
    }

    /** 旧版本只有单个 elderId，迁移成名单里的一条 */
    private void migrateLegacy() {
        String id = str(sp.getString("elderId", ""));
        if (id.length() == 0) id = "demo";
        List<Elder> one = new ArrayList<>();
        one.add(new Elder(id,
                orDefault(str(sp.getString("elderName", "")), "妈妈"),
                str(sp.getString("elderPhone", "")),
                str(sp.getString("childToken", ""))));
        save(one, id);
    }

    // ==========================================================================
    //  兼容老调用点：一律转成"当前这位老人"
    // ==========================================================================

    public String elderId() { return current().id; }

    /** 旧签名保留：改的是当前老人的编号，正常流程不会用到 */
    public void elderId(String v) {
        String t = str(v);
        if (t.length() == 0) t = "demo";
        Elder c = current();
        if (c.id.equals(t)) return;
        List<Elder> all = elders();
        for (Elder e : all) {
            if (e.id.equals(t)) { save(all, t); return; }
        }
        all.add(new Elder(t, "老人" + (all.size() + 1), "", ""));
        save(all, t);
    }

    /** 配对凭证：用老人端给的 6 位配对码换来，只能查看这一位老人的预警 */
    public String childToken() { return current().token; }

    public void childToken(String v) { upsert(current().id, str(v)); }

    /** 有没有完成配对（没配对时看的是公开演示编号 demo） */
    public boolean paired() { return childToken().length() > 0; }

    /** 怎么称呼老人（妈妈 / 爸爸 / 奶奶…），界面上所有"妈妈"都用它 */
    public String elderName() { return orDefault(str(current().name), "妈妈"); }

    public void elderName(String v) { name(current().id, v); }

    /** 老人的手机号，"打给妈妈"按钮用。没填就空着，按钮会提示去设置 */
    public String elderPhone() { return str(current().phone); }

    public void elderPhone(String v) { phone(current().id, v); }

    // ==========================================================================
    //  全局设置
    // ==========================================================================

    /** 子女点过"已处理"的提醒（只存在本机，不影响老人端） */
    public boolean handled(long id) { return sp.getBoolean("handled_" + id, false); }

    public void handled(long id, boolean v) { sp.edit().putBoolean("handled_" + id, v).apply(); }

    /** 新提醒到的时候要不要响一声。答辩现场可以关，避免录音里全是铃声 */
    public boolean sound() { return sp.getBoolean("sound", true); }

    public void sound(boolean v) { sp.edit().putBoolean("sound", v).apply(); }

    // ==========================================================================

    private static String str(String s) { return s == null ? "" : s.trim(); }

    private static String orDefault(String s, String def) {
        return s == null || s.trim().length() == 0 ? def : s.trim();
    }
}
