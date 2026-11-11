package com.yindun.guard;

import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Guard —— 全局调度中心。整个 App 的"值班室"
 * ============================================================
 * 【它解决什么问题】
 *   我们有五个采集器（短信、通话、前台App、装包、无障碍），
 *   它们互相不认识，各自在各自的线程里被系统叫醒。
 *
 *   但判定需要"它们加在一起"的信息：
 *     "正在和陌生人通话" + "同时打开了银行" = 危险
 *   前半句是通话采集器知道的，后半句是前台应用采集器知道的。
 *
 *   所以必须有一个地方把两句话拼起来。这个地方就是 Guard。
 *
 * 【数据流】
 *   采集器 → 更新 Guard 里的 State → Guard 跑一遍判定 → 级别变了就干预
 *
 *   三个动作各自独立，谁改了都不会影响别人，加新采集器只需在 State 里加一个字段。
 *
 * 【一个关键设计：只在"级别上升"时动手】
 *   如果每次状态变化都推一次通知，子女会被轰炸：通话时间每过一秒都变，
 *   难道每秒推一条？
 *
 *   所以只有"级别比上次高"才动手。级别降了或者不变，什么也不做。
 *   这也符合安全产品的直觉：警报只在升级时响一次。
 * ============================================================
 */
public class Guard {

    private static final String TAG = "YinDun";
    private static Guard inst = null;

    public static synchronized Guard get(Context c) {
        if (inst == null) inst = new Guard(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;

    /** 当前手机状态。所有采集器往这里写，判定只从这里读。 */
    public final Judge.State st = new Judge.State();

    /** 本次通话：开始时刻（毫秒）和对方号码 */
    public long callStartAt = 0;
    public String callNumber = null;

    /** 上一次的级别排名，用来判断"有没有升级" */
    private int lastRank = 0;

    /**
     * 判定倍速。真实 1 秒判作 N 秒，只喂给判级用（显示给人看的一律用 realDur）。
     *
     * 【为什么默认值要分环境】
     * 这个值原本写死 20，是为了在模拟器上几十秒走完"聊满 5/8 分钟"的流程。
     * 但现在这套要装上真机：真机上如果还是 20 倍，老人接个电话 15 秒就被判成
     * "聊满 5 分钟"、24 秒就跳到警告——等于把正常通话全判成诈骗。
     * 所以真机自动按 1 倍（真实时间）跑，模拟器上仍然 20 倍。
     * 装机后要演示，可以在设置里手动改，优先级高于这里的默认值。
     */
    private static final boolean IS_EMULATOR = detectEmulator();

    /** 是否跑在模拟器上（演示模式、演示号码只在模拟器上默认启用） */
    public static boolean isEmulator() {
        return IS_EMULATOR;
    }
    private static final int DEFAULT_SPEED = IS_EMULATOR ? 20 : 1;

    private int demoSpeed = DEFAULT_SPEED;

    /** 跑在模拟器上吗（真机上这几条属性都不会出现） */
    private static boolean detectEmulator() {
        String fp = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        String hw = Build.HARDWARE == null ? "" : Build.HARDWARE;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        return fp.startsWith("generic")
                || fp.contains("emulator")
                || hw.contains("goldfish")
                || hw.contains("ranchu")
                || model.contains("sdk_gphone")
                || model.contains("Emulator");
    }

    /** 本次通话里已经报过的关注级，避免重复报 */
    private boolean reported5min = false;
    private boolean reported8min = false;

    /**
     * 本次通话里，第二次询问（聊满 5 分钟那次）问过了吗。
     *
     * 【为什么只问一次】
     * 2026-11-07 定的产品规则：一共问两次——响铃一次、通话中一次。
     * 再加就变成骚扰，老人会烦到卸载；而真正的兜底不在口头上，
     * 在她打开手机银行那一刻的盖屏。
     */
    private boolean askedInCall = false;

    /**
     * 这一通来电，响铃时那条「陌生号码来电」报给子女了吗。
     *
     * 【2026-11-09 为什么加这个字段】
     * 原来只有当 st.callWho 有值时判定器才认出"陌生号码通话中"，
     * 而 callWho 是"接通"（state==2）那一刻才赋值的。
     * 结果：电话响铃的十几秒里，子女端一条推送都收不到——
     * 恰恰风险刚开始的那一段是静默的，实测就是这么发现的。
     *
     * 现在响铃就报一次。系统可能对同一通来电回调多次 state==1，
     * 所以要用这个标记挡住重复推送。
     */
    private boolean rangAlerted = false;

    private Guard(Context c) {
        this.ctx = c;
        try {
            demoSpeed = c.getSharedPreferences("yindun", Context.MODE_PRIVATE)
                    .getInt("demo_speed", DEFAULT_SPEED);
        } catch (Exception ignored) {
        }
        if (demoSpeed < 1) demoSpeed = 1;
        Log.i(TAG, "判定用通话时长倍速 ×" + demoSpeed
                + (demoSpeed > 1 ? "（演示模式：真实 1 秒算 " + demoSpeed + " 秒，只影响判级，不影响真实计时）"
                                 : "（真实时间" + (IS_EMULATOR ? "·模拟器" : "·真机") + "）"));
    }

    /** 演示倍速：1 = 真实时间，20 = 真实 1 秒记作 20 秒 */
    public static void setDemoSpeed(Context c, int n) {
        int v = n < 1 ? 1 : n;
        c.getSharedPreferences("yindun", Context.MODE_PRIVATE)
                .edit().putInt("demo_speed", v).apply();
        Guard g = get(c);
        g.demoSpeed = v;
        Log.i(TAG, "演示倍速改为 ×" + v);
    }

    public int demoSpeed() {
        return demoSpeed;
    }

    // ==========================================================
    //  一、采集器调用的入口
    // ==========================================================

    /**
     * 通话状态变了。
     *
     * @param state  1=响铃 2=通话中 0=挂断
     * @param number 对方号码（可能为 null，系统不给）
     * @param who    号码身份 stranger / contact / service
     */
    public void onCallState(int state, String number, String who) {
        if (state == 2) {
            // ---- 开始通话 ----
            // 接起来了，熟人询问就没意义了：手机一贴耳朵屏幕就黑，问了他也看不见
            Acquaint.dismiss();
            if (callStartAt == 0) {
                startNewCall(number, who);
            } else if (number != null && !number.equals(callNumber)) {
                // 【2026-11-08 修】呼叫等待：老人通话中又接起另一通电话时，
                // 系统的 state 一直是 2，不会走"挂断"分支，
                // 于是下面这段"只对单通电话成立"的状态一个都不会重置。
                // 实测后果有两个：第二通电话永远不会弹复问卡；
                // 红屏上写的"对方号码"也还是第一通的号码（报错人）。
                Log.i(TAG, "又接起一通电话 " + callNumber + " → " + number + "，按新通话重置");
                startNewCall(number, who);
            }
        } else if (state == 1) {
            // ---- 有电话打进来 ----
            if (callStartAt > 0) {
                // 正在通话时又来了一个电话，记一笔。是不是家人由 CallLog 事后确认。
                EventBus.push(event("call", "state", "ringing2", null, number, realDur(), null));
            } else {
                // 全新的一通来电，正在响铃。
                //
                // 【为什么熟人询问只能放在这里】
                // 老人一接起来，手机贴到耳朵上，距离感应器会把屏幕熄掉。
                // 那之后屏幕上出现任何东西他都看不见——盖屏看不见，弹窗也看不见。
                // 响铃这几秒是全场唯一"屏幕亮着、人正盯着看、手还没被占住"的时刻。
                Acquaint.askIfNeeded(ctx, number, who);

                // 【2026-11-09 修】陌生号一响铃就报给子女。
                //
                // 原来这里什么都不发：st.callWho 要等"接通"才赋值，
                // 所以判定器在响铃阶段认不出"陌生号码"，
                // 子女端从响铃到接通后第一次 tick 之间是完全静默的。
                // 实测：拨进一通陌生号，手机端首问卡弹了，子女端一条都没多。
                //
                // 为什么不等接通再报：响铃就已经是风险开始的时刻——
                // 骗子往往让老人"先别挂，听我说完"。子女早十几秒知道，就能早点打进来。
                if (!rangAlerted && NumberInfo.STRANGER.equals(who)) {
                    rangAlerted = true;
                    Log.i(TAG, "陌生号码响铃 → 立刻通知子女 " + number);
                    // 时间轴从这里开始算。响铃就开一场新的：这是"陌生号打进来"这个事实本身，
                    // 之后接通时 startNewCall 会复用同一个 id（见 CallSession.reuseOrBegin）。
                    CallSession.begin(number);
                    Notifier.alertChild(ctx, Notifier.K_RING, "watch",
                            "妈妈收到陌生号码来电",
                            "来电号码不在通讯录中，正在响铃。接听后银盾将持续监测。");
                }
            }
        } else {
            // ---- 挂断 ----
            Acquaint.dismiss();
            TipBar.hide();      // 通话结束，轻提醒没有留着的道理

            // 【2026-11-09 修】不管接没接，这一通都结束了，响铃标记必须复位。
            // 否则"响铃没接就挂断"会让标记一直挂着，下一通陌生来电
            // 被当成"已经报过"，子女端再也收不到那一通的通知。
            rangAlerted = false;

            // 骗子的电话挂了 —— 这一场风险窗口就结束了。
            // 盖屏不能一直架在那儿：老人会以为手机坏了，甚至以为被锁死不敢用。
            // 留 3 秒（够她看清"刚才被拦了一次"），然后自动撤屏。
            // 之前没有这一步，实测的结果是：挂断后红屏一直挂着，演示到这儿就卡死了。
            if (Shield.showing()) {
                Log.i(TAG, "通话已结束，3 秒后撤掉盖屏");
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Shield.hide(ctx);
                            }
                        }, 3000);
            }

            if (callStartAt > 0) {
                long d = realDur();
                Log.i(TAG, "通话结束 真实时长=" + d + "秒");
                EventBus.push(event("call", "state", "idle", st.callWho, callNumber, d, null));
                // 挂断后复盘：去找系统自带的通话录音，交给云端判断（只处理陌生号码、需老人开启）
                CallRecord.onCallEnded(ctx, callNumber, st.callWho, callStartAt);
                callStartAt = 0;
                callNumber = null;
                st.callWho = null;
                st.callDur = 0;
                st.codeInCall = false;
                lastRank = 0;
                askedInCall = false;
            }
        }
        evaluate("call");
    }

    /**
     * 开一场新的通话：把所有"只对单通电话成立"的状态归零。
     *
     * 【为什么单独抽出来】
     * 这些状态原来写在 state==2 分支里，靠 callStartAt==0 判断"是不是新通话"。
     * 但呼叫等待时 callStartAt 永远不为 0，那段代码就整段被跳过了。
     * 抽成方法后，两条入口（全新通话 / 换了一通电话）走完全一样的重置逻辑。
     */
    private void startNewCall(String number, String who) {
        callStartAt = System.currentTimeMillis();
        callNumber = number;
        st.callWho = who;
        st.callDur = 0;
        st.rejectFamily = 0;
        st.codeInCall = false;
        reported5min = false;
        reported8min = false;
        askedInCall = false;
        lastRank = 0;   // 新的一通电话就是新的风险窗口，判级重新开始
        // 给这一场开个 id：本次通话期间的上报都带上，子女端才能把
        // 「响铃→复问→验证码→打开银行→红屏→呼叫女儿」串成一条时间轴。
        String cid = CallSession.reuseOrBegin(number);
        Log.i(TAG, "通话开始 who=" + who + " num=" + number + " 会话=" + cid);
        EventBus.push(event("call", "state", "offhook", who, number, 0, null));
    }

    /** 前台换成了银行/支付类 App */
    public void onBankForeground(String pkg, String label) {
        if (st.bankForeground) return;   // 已经在银行里，不用重复报
        st.bankForeground = true;
        Log.i(TAG, "前台出现银行类 App: " + label + " (" + pkg + ")");
        EventBus.push(event("app", "pkg", pkg, null, null, 0, label));
        evaluate("bank");

        // 电话已经挂了，但刚才那通被录音复盘判成了诈骗 —— 骗子常让老人挂断后再去转账。
        // 通话中打开银行由上面的组合判据处理，这里只管"挂断之后"这一段。
        if (!st.inCall() && CallRecord.riskActive() && !Shield.showing()) {
            String num = CallRecord.riskNumber();
            String from = CallRecord.riskFrom();     // 非空 = 这条风险窗口是子女远程开的
            Acquaint.dismiss();
            TipBar.hide();
            if (from != null) {
                // 子女远程锁的转账：别跟老人说"刚才那通电话有诈"，她可能根本没在外地那一通里。
                // 只说事实：家里人不让她这会儿动钱。
                Log.i(TAG, "子女远程锁定的窗口内打开银行 → 盖屏");
                Notifier.alertChild(ctx, Notifier.K_BANK, "high", "妈妈在转账保护期内打开了手机银行",
                        from + " 刚才开启了转账保护，现在老人打开了「" + label
                                + "」。屏幕已拦截，建议立刻致电确认。");
                Shield.show(ctx, "先别转账", null, "家人暂时锁定了转账");
            } else {
                Log.i(TAG, "挂断可疑电话后打开了银行 → 盖屏");
                // 用 high 推子女（不在这里拨号）：盖屏 6 秒无人操作会自己拨，避免拨两次
                Notifier.alertChild(ctx, Notifier.K_BANK, "high", "妈妈在可疑通话结束后打开了手机银行",
                        "刚才的陌生来电经通话复盘判定为疑似诈骗，目前已打开「" + label
                                + "」。屏幕已拦截，请立即致电。");
                Shield.show(ctx, "暂停转账", num);
            }
        }
    }

    /** 前台离开了银行类 App */
    public void onLeaveBank() {
        st.bankForeground = false;
    }

    /** 装了非应用商店的 APK */
    public void onUnknownInstall(String pkg) {
        if (st.unknownInstall) return;
        st.unknownInstall = true;
        Log.i(TAG, "装了非商店 App: " + pkg);
        EventBus.push(event("install", "pkg", pkg, null, null, 0, null));
        evaluate("install");
    }

    /** 无障碍权限状态变了（针对刚装的那个 App） */
    public void onA11y(boolean granted, String pkg) {
        if (st.a11yGranted == granted) return;
        st.a11yGranted = granted;
        Log.i(TAG, "无障碍授权=" + granted + " pkg=" + pkg);
        EventBus.push(event("a11y", "granted", String.valueOf(granted), null, null, 0, pkg));
        evaluate("a11y");
    }

    /** 本次通话中收到验证码短信 */
    public void onCodeInCall() {
        if (st.codeInCall) return;
        st.codeInCall = true;
        Log.i(TAG, "通话中收到验证码短信");
        evaluate("code");
    }

    // ==========================================================
    //  二、时间兜底
    // ==========================================================
    /**
     * 由 CallWatcher 每 5 秒调一次。
     *
     * 【为什么要定时调】
     * 因为"陌生号码通话满 5 分钟"这件事，没有任何一个"事件"来触发它——
     * 它就是时间到了。所以必须有人主动去看表。
     *
     * 【为什么是 5 秒不是 1 秒】
     * 5 分钟的门槛，误差 5 秒无所谓。每秒查一次是浪费电，
     * 老人手机最怕耗电，耗电就会被卸载。
     */
    public void tick() {
        if (callStartAt == 0) return;
        st.callDur = (int) dur();

        // 老人端也要看到提示，不能只报给子女（这是老板明确要求的）
        if (!reported5min && st.callDur >= 300) {
            reported5min = true;
            EventBus.push(event("call", "state", "watch5", st.callWho, callNumber, realDur(), null));
        }

        // ---------- 第二次询问 ----------
        // 陌生号码聊满 5 分钟，再问一次"是不是认识的人"。
        //
        // 【为什么用 st.callDur 而不是 realDur】
        // st.callDur 是喂给判级的时长（演示模式下被放大 20 倍），
        // 这样模拟器上三四十秒就能跑到"满 5 分钟"，真机上是真 5 分钟。
        //
        // 【为什么只对 stranger 弹】
        // 通讯录里的号码（contact/service）不弹。老人存了谁的号，
        // 我们没理由怀疑；弹了反而让她觉得"手机上的人都不许信"。
        // 红屏已经盖着就不再问：两张卡叠在一起，老人反而不知道看哪个
        if (!askedInCall && st.callDur >= 300 && !Shield.showing()
                && callNumber != null && NumberInfo.STRANGER.equals(st.callWho)) {
            askedInCall = true;
            Log.i(TAG, "通话满 5 分钟 → 第二次询问（持续振动，点按钮才停）");
            Acquaint.askInCall(ctx, callNumber);
        }

        evaluate("tick");
    }

    // ==========================================================
    //  三、判定 + 干预
    // ==========================================================
    /**
     * 跑一遍组合判据，级别升级了就动手。
     *
     * @param trigger 谁触发的（只用于日志，方便排查）
     */
    /**
     * 这次判级是被什么触发的 —— 决定子女端时间轴上的图标。
     *
     * 【为什么要区分】"妈妈正在进行高风险操作"这句话，可能是"打开了手机银行"、
     * 也可能是"通话中收到验证码"或"聊得太久"。文本上看不出来，时间轴上就只剩
     * 一排长得一模一样的"风险升级"。按触发源分开标，子女端才能一眼看出
     * "她是去转账了"，而不是"她又聊了五分钟"。
     */
    private static String kindOf(String trigger) {
        if ("bank".equals(trigger)) return Notifier.K_BANK;
        if ("code".equals(trigger)) return Notifier.K_CODE;
        return Notifier.K_JUDGE;
    }

    private void evaluate(String trigger) {
        Judge.Result r = Judge.get(ctx).judgeState(st);
        int rank = rank(r.level);

        if (rank <= lastRank) return;    // 没升级，什么都不做
        lastRank = rank;

        Log.i(TAG, "级别升到 " + r.level + "（触发源=" + trigger + "）原因=" + r.why);

        // 老人此刻看得见屏幕吗？
        // 她接起电话就会把手机贴到耳朵上，距离感应器把屏幕熄掉——
        // 从那一刻到通话结束，屏幕上盖着多大的红字她都看不见。
        // 所以先问一句，再决定要振多重。
        boolean screenOff = Vibrate.screenOff(ctx);

        // ---------- 1. 推给界面（子女端会显示一张卡） ----------
        EventBus.pushAlert(r.level, r.why, r.stage, r.score);

        // ---------- 2. 通知子女 ----------
        // 阻断级走"打电话"，其余走通知。因为推送能不能看到取决于子女在不在看手机，
        // 铃声只取决于手机有没有电。
        if ("block".equals(r.level)) {
            Notifier.alertChild(ctx, kindOf(trigger), r.level, buildTitle(r), buildBody(r));

            Log.i(TAG, "阻断判定，屏幕" + (screenOff
                    ? "是黑的（她贴着耳朵，看不见盖屏）→ 全靠振动叫"
                    : "亮着（她看得见盖屏）"));

            // ---------- 3. 盖住老人的屏幕 ----------
            // 振动由 Shield.show 内部一起起——两者同时开始，
            // 然后一直振，振到老人自己按"停止振动"，或盖屏被撤掉。
            // 不用再在这里调 Vibrate，否则会叠两遍。
            // 盖屏前先撤掉熟人询问卡，否则两张悬浮窗会叠在一起（红屏被青卡压住一截）
            Acquaint.dismiss();
            TipBar.hide();      // 红屏上来，轻提醒让位
            Shield.show(ctx, buildShieldTitle(r), callNumber);

            // ---------- 4. 请华为云盘古补一句"人话"----------
            // 注意是异步的：通知已经发出去了，这句话晚 1~3 秒到，
            // 到了就贴在子女端那张卡上。没到也不影响什么。
            aiExplain(r);
        } else if ("high".equals(r.level) || "warn".equals(r.level)) {
            Notifier.alertChild(ctx, kindOf(trigger), r.level, buildTitle(r), buildBody(r));
            // 通话超时这类恰好发生在"正贴着耳朵听"的时候，屏幕多半是黑的
            Vibrate.remind(ctx, screenOff);
            // 【2026-11-05 补】老人端也得看得见。
            // 之前这两级只发系统通知，可老人正贴着耳朵通话，
            // 通知在状态栏里她根本不会去拉——等于中间两级对她不存在。
            //
            // 【2026-11-08 补】复问卡如果正显示着，浮条让开：
            // 两张都弹在屏幕上部，叠在一起会互相压字，而复问卡更要紧。
            if (!Acquaint.inCallShowing()) {
                TipBar.show(ctx, r.level, tipTitle(r), tipSub(r));
            }
        }
        // watch 级（满5分钟）只推卡，不打扰子女，也不振动
        else {
            // 响铃那一刻已经报过「接到陌生号码来电」，这里不再重复报同一条。
            // （否则同一通电话，子女端会收到两张标题一模一样的卡）
            // 但老人端的浮条照弹：她接通后需要知道"这个号码不认识"。
            if (!rangAlerted) {
                Notifier.alertChild(ctx, kindOf(trigger), r.level, buildTitle(r), buildBody(r));
            }
            if (!Acquaint.inCallShowing()) {
                TipBar.show(ctx, r.level, tipTitle(r), tipSub(r));
            }
        }
    }

    /** 贴在老人屏幕上的那句轻提醒（和推给子女的话不是一回事） */
    private String tipTitle(Judge.Result r) {
        if ("high".equals(r.level)) return "检测到可远程控制屏幕的应用";
        if ("warn".equals(r.level)) {
            // 【2026-11-07 改】原来写"已经聊了 X 分钟"。
            // 演示是 ×20 倍速，浮条写"聊了 8 分钟"时，底下通话界面的计时才 00:26，
            // 投到大屏上一眼就穿帮。浮条要紧的是"该挂断了"，不是精确分钟数。
            return "本次通话时间较长";
        }
        return "来电号码不在通讯录中";
    }

    private String tipSub(Judge.Result r) {
        // 【2026-11-07 改】原来写的是"女儿已经在打你电话""女儿那边已经知道了"。
        // 这两句在没有网/服务器没起的时候是假的——推送早就失败了。
        // 演示时投影到大屏上，评委一问"女儿收到什么了"就穿帮。
        // 所以统一改成"正在叫"：这是我们真的在做的事，不是宣称已经完成的事。
        if ("high".equals(r.level)) return "请勿按对方要求操作 · 正在通知女儿";
        if ("warn".equals(r.level)) {
            return "请勿透露个人信息 · 正在通知女儿";
        }
        return "请勿透露家庭与财产信息";
    }

    private String buildTitle(Judge.Result r) {
        if ("block".equals(r.level)) return "妈妈正在进行高风险操作";
        if ("high".equals(r.level))  return "妈妈的手机安装了远程控制应用";
        if ("warn".equals(r.level))  return "妈妈与陌生号码通话时间过长";
        return "妈妈接到一个陌生号码来电";
    }

    private String buildBody(Judge.Result r) {
        String extra = (r.why == null || r.why.length() == 0) ? "" : "（" + r.why + "）";
        if ("block".equals(r.level)) {
            return "通话期间出现涉及资金的操作" + extra + "。屏幕已拦截，建议立即致电。";
        }
        if ("high".equals(r.level)) {
            return "检测到非应用商店安装的应用，并已开启无障碍权限" + extra + "。请立即致电确认。";
        }
        if ("warn".equals(r.level)) {
            return "陌生号码，" + durPhrase() + "，建议致电了解情况。";
        }
        return "陌生号码来电，" + durPhrase() + "。仅作提醒，暂无需处理。";
    }

    /**
     * 给子女看的时长短语。
     * 【踩过的坑】原来直接写 (realDur()/60) + " 分钟"，不到 1 分钟就显示"0 分钟"。
     * 而且规则里的 why 原本写着"通话满 8 分钟"，演示是倍速触发，
     * 卡片上就出现"已经聊了不到 1 分钟（陌生号码通话满 8 分钟）"这种自相矛盾。
     * 现在 why 不再带分钟数，句子也单独成型。
     */
    private String durPhrase() {
        long m = realDur() / 60;
        return m < 1 ? "通话不到 1 分钟" : "通话 " + m + " 分钟";
    }

    private String buildShieldTitle(Judge.Result r) {
        if (r.why != null && r.why.contains("验证码")) return "验证码已隐藏";
        if (r.why != null && r.why.contains("银行"))   return "暂停转账";
        return "请暂停操作";
    }

    // ==========================================================
    //  三·五、华为云盘古：给子女补一句人话
    // ==========================================================
    /**
     * 把"陌生号 + 18 分钟 + 打开银行 + 收到验证码"这堆专业事件，
     * 翻成一句子女一秒能看懂的话。
     *
     * 【为什么这件事不能让本地规则做】
     * 本地规则只会拼模板："妈妈通话中打开了手机银行"
     * 模型能说出："她可能正在被冒充公检法的人引导转账"
     * 前者是描述，后者是判断。子女需要的是后者。
     *
     * 【为什么不发第二条通知】
     * 阻断级那条通知已经够醒目了，再发一条反而吵。
     * 这句话推到子女端界面的卡片上贴着，她点进来就看见。
     *
     * 【失败了怎么办】
     * 什么都不办。本地模板给的那句话本来就是完整的，
     * 模型只是让它更好懂，不是必需项。
     */
    private void aiExplain(final Judge.Result r) {
        if (!Pangu.configured(ctx)) return;
        // 没网也别试，省得白等 5 秒
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return;
        } catch (Exception ignored) {
        }

        final String events = Pangu.eventsOf(st, r.why);
        Log.i(TAG, "请盘古解释：" + events);

        Pangu.explain(ctx, events, new Pangu.Callback() {
            @Override
            public void ok(String text) {
                Log.i(TAG, "盘古说：" + text);
                EventBus.pushAI("explain", text, true);
            }
            @Override
            public void fail(String reason) {
                Log.w(TAG, "盘古解释失败：" + reason);
                EventBus.pushAI("explain", reason, false);
            }
        });
    }

    // ==========================================================
    //  四、工具
    // ==========================================================
    /**
     * 真实通话秒数。
     * 【为什么要有它】判级用 ×20 倍速（否则演示要真等 8 分钟），
     * 但凡是要显示给人看的数字——浮条、子女端卡片、网页时间线——必须是真数。
     * 否则手机计时 00:26、卡片写"聊了 8 分钟"，同屏自相矛盾。
     */
    private long realDur() {
        if (callStartAt == 0) return 0;
        return (System.currentTimeMillis() - callStartAt) / 1000;
    }

    private long dur() {
        return realDur() * demoSpeed;
    }

    private int rank(String level) {
        if ("block".equals(level)) return 4;
        if ("high".equals(level))  return 3;
        if ("warn".equals(level))  return 2;
        if ("watch".equals(level)) return 1;
        return 0;
    }

    /** 拼一个推给网页的事件 JSON */
    private String event(String type, String key, String val, String who, String num, long d, String extra) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", type);
            o.put(key, val);
            if (who != null) o.put("who", who);
            if (num != null) o.put("number", num);
            o.put("dur", d);
            o.put("clock", new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date()));
            if (extra != null) o.put("label", extra);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
