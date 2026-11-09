package com.yindun.child;

/**
 * 一条提醒。字段和云端 /api/alerts 返回的列一一对应，没有多余的加工。
 *
 * level 含义（和老人端共用同一套规则引擎）：
 *   watch  留意   —— 有点苗头，不打扰
 *   warn   提醒   —— 已经不对劲，要告诉子女
 *   high   高危   —— 大概率是诈骗
 *   block  阻断   —— 老人手机已经被盖屏拦下来了
 *
 * channel：
 *   push       来自短信 / 微信消息 / 应用安装
 *   push+call  来自通话（含银行拦截），子女端要显示成「电话」
 */
public class Alert {

    public long id;
    public String level = "watch";
    public String title = "";
    public String body = "";
    public String why = "";
    public String summary = "";      // 云端大模型补的一句人话，可能为空
    public String channel = "push";
    public String time = "";         // 已格式化好的 HH:mm，直接上屏
    public long ts;                  // 本地收到时刻，只用于排序兜底

    /** 是不是电话类 */
    public boolean isCall() {
        return "push+call".equals(channel);
    }

    public String levelWord() {
        if ("block".equals(level)) return "已阻断";
        if ("high".equals(level)) return "高危";
        if ("warn".equals(level)) return "提醒";
        return "留意";
    }

    public String channelWord() {
        // 【为什么不直接用 channel 字段】
        // 库里的 channel 值是 push / push+call / upload / drill，它表达的是
        // 「服务端要不要顺手给子女打个电话」，不是「这条事来自哪个入口」。
        // 拿它当标签会出现「妈妈接到陌生来电」被标成"消息"这种笑话。
        // 所以按标题正文里的词判断来源 —— 这些文案是我们自己写的，措辞固定，判得准。
        String t = (title == null ? "" : title) + (body == null ? "" : body);
        if (t.contains("微信")) return "微信";
        if (t.contains("短信")) return "短信";
        if (t.contains("通话") || t.contains("来电") || t.contains("电话")) return "电话";
        if (t.contains("银行") || t.contains("转账")) return "转账";
        return isCall() ? "电话" : "消息";
    }

    /** 等级排序权重，越大越急 */
    public int rank() {
        if ("block".equals(level)) return 4;
        if ("high".equals(level)) return 3;
        if ("warn".equals(level)) return 2;
        return 1;
    }
}
