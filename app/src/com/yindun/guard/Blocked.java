package com.yindun.guard;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记下"刚才被判为诈骗的短信"，供通知监听那边比对。
 * ============================================================
 * 【为什么需要这样一个类】
 * 一条短信在银盾里走的是两条完全不相识的链路：
 *
 *   短信广播（SmsReceiver）──→ 判定为诈骗 ──→ 弹浮条、推子女
 *   系统短信 App 自己发的通知（NotiGuard 能看到）
 *
 * 判定只说"这条是诈骗"，通知栏里那条却仍然长得和正常短信一模一样，
 * 老人下拉一看，跟银行发的没区别。这个类就是两边之间的对照表：
 * 广播那边写进来，通知那边查一下，查到就把系统那条换掉。
 *
 * 【为什么只存前缀，不存全文】
 * 通知里的正文本来就可能被系统截断（"这里是市公安局，您的账户涉嫌洗钱…"），
 * 按整条比会匹配不上。只留前 12 个字够区分了 —— 短信开头几乎不会重样。
 * 顺带的好处：老人手机里不多存一份正文副本。
 *
 * 【为什么放内存，不落盘】
 * SmsReceiver 和 NotiGuard 同在银盾这一个进程里，短信广播到通知生成只隔一两秒。
 * 进程被杀时记录丢了也不影响任何行为：那条通知早就弹过去了。
 * 反过来，落盘意味着每次开机都要扫一遍过期记录，为这几条数据不值得。
 *
 * 【为什么取走就删】
 * 系统短信 App 收到同一条短信可能重发通知（比如用户划掉后再看）。
 * 一条被判过的短信只需要换一次；不删掉的话，老人自己划掉那条警告后，
 * 短信 App 重发通知又会换一次，来回拉扯。
 * ============================================================
 */
final class Blocked {

    /** 最多记 10 条，超了挤掉最早的。老人一天被 10 条以上诈骗短信找上，已经是极端情况 */
    private static final int MAX = 10;

    /** 超过 24 小时不再匹配：隔天的通知重发没有替换的意义 */
    private static final long TTL = 24 * 3600 * 1000L;

    /** 正文最短取这么多字做指纹。太短的（"在吗"）没有区分度 */
    private static final int PREFIX = 12;

    /** 命中判定要求至少连续相同这么多字 */
    private static final int MIN_HIT = 8;

    /** 包内可见：NotiGuard 要读 from 字段 */
    static final class Item {
        final String prefix;
        final String from;
        final long at;

        Item(String prefix, String from, long at) {
            this.prefix = prefix;
            this.from = from;
            this.at = at;
        }
    }

    private static final Map<String, Item> items =
            new LinkedHashMap<String, Item>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Item> eldest) {
                    return size() > MAX;
                }
            };

    private Blocked() {
    }

    /**
     * 记一条。由 SmsReceiver 在判到 block 时调用。
     *
     * @param from 发件号码（可能为空）
     * @param body 短信正文
     */
    static void add(String from, String body) {
        String p = norm(body);
        if (p.length() < MIN_HIT) return;
        if (p.length() > PREFIX) p = p.substring(0, PREFIX);
        synchronized (items) {
            items.put(p, new Item(p, from == null ? "" : from, System.currentTimeMillis()));
        }
    }

    /**
     * 这条通知正文是不是刚被判过诈骗。命中即从表里移除（一条只换一次）。
     *
     * @return 命中的记录，没有则 null
     */
    static Item take(String body) {
        String t = norm(body);
        if (t.length() < MIN_HIT) return null;

        long now = System.currentTimeMillis();
        synchronized (items) {
            Iterator<Map.Entry<String, Item>> it = items.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Item> e = it.next();
                Item v = e.getValue();
                if (now - v.at > TTL) {
                    it.remove();
                    continue;
                }
                // 两边都可能被截断，所以比"公共前缀"而不是比"谁包含谁"
                if (common(t, v.prefix) >= MIN_HIT) {
                    it.remove();
                    return v;
                }
            }
        }
        return null;
    }

    /** 当前记了几条。只给日志和测试用 */
    static int size() {
        synchronized (items) {
            return items.size();
        }
    }

    /** 清空。配对重置、演示脚本重跑时用 */
    static void clear() {
        synchronized (items) {
            items.clear();
        }
    }

    /** 把 24 小时前的旧记录清掉。GuardService 心跳里偶尔调一次就够 */
    static void sweep() {
        long now = System.currentTimeMillis();
        synchronized (items) {
            List<String> dead = new ArrayList<String>();
            for (Map.Entry<String, Item> e : items.entrySet()) {
                if (now - e.getValue().at > TTL) dead.add(e.getKey());
            }
            for (String k : dead) items.remove(k);
        }
    }

    /** 去掉空白，避免系统通知里多余的空格导致对不上 */
    private static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u3000]+", "");
    }

    /** 两个字符串从头开始连续相同多少个字 */
    private static int common(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }
}
