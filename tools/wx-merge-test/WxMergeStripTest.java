public class WxMergeStripTest {

    /** 与 NotiGuard.stripMergeWrapper 完全一致，只用于验证剥离逻辑 */
    private static String stripMergeWrapper(String body) {
        String s = body == null ? "" : body.trim();

        if (s.matches("^\\d+\\s*条新消息.*$")) return "";

        if (!s.matches("^\\[\\s*\\d+\\s*条\\s*\\].*$")) return s;

        s = s.replaceFirst("^\\[\\s*\\d+\\s*条\\s*\\]", "").trim();

        int c = s.indexOf('：');
        int e = s.indexOf(':');
        if (c < 0) {
            c = e;
        } else if (e >= 0 && e < c) {
            c = e;
        }
        if (c > 0 && c <= 24 && c < s.length() - 1) {
            String rest = s.substring(c + 1).trim();
            rest = rest.replaceFirst("^\\d+\\s*[.、]\\s*", "");
            if (rest.length() >= 4) return rest;
        }
        return s.replaceFirst("^\\d+\\s*[.、]\\s*", "");
    }

    private static int pass = 0, fail = 0;

    private static void t(String label, String in, String expect) {
        String got = stripMergeWrapper(in);
        boolean ok = got.equals(expect);
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        System.out.println("      in  = [" + in + "]");
        if (!ok) System.out.println("      got = [" + got + "]\n      exp = [" + expect + "]");
    }

    public static void main(String[] args) {
        // ===== 真机截图里的两种真实格式（必须判出来）=====
        t("EMUI 合并2条·索款", "[2条]-zzz～y：我临时遇到急事，不方便打电话，先转300到这个卡号：",
                "我临时遇到急事，不方便打电话，先转300到这个卡号：");
        t("EMUI 合并3条·带序号", "[3条]-zzz～y：4.【招聘兼职】线上数据录入，日结300",
                "【招聘兼职】线上数据录入，日结300");

        // ===== 回归：单条通知一个字符都不能改 =====
        t("单条·含冒号正文", "我临时遇到急事，先转300到这个卡号：6222 0000 1111",
                "我临时遇到急事，先转300到这个卡号：6222 0000 1111");
        t("单条·注意开头", "注意：您的快递已到驿站，请凭取件码领取",
                "注意：您的快递已到驿站，请凭取件码领取");
        t("单条·普通聊天", "晚上回家吃饭吗", "晚上回家吃饭吗");

        // ===== 纯汇总：确实没内容，跳过 =====
        t("纯汇总·N条新消息", "3 条新消息", "");
        t("纯汇总·带空格", "12条新消息", "");

        // ===== 群消息合并：两层冒号只能剥一层 =====
        t("群消息合并", "[2条]家庭群：张三：晚上回家吃饭吗", "张三：晚上回家吃饭吗");

        // ===== 边界 =====
        t("空串", "", "");
        t("只有前缀", "[2条]", "");
        t("前缀+冒号无内容", "[2条]张三：", "张三：");
        t("冒号在很后面（属正文）", "[2条]张三：卡号是：6222 0000 1111",
                "卡号是：6222 0000 1111");

        System.out.println("\n==== " + pass + " pass, " + fail + " fail ====");
        if (fail > 0) System.exit(1);
    }
}
