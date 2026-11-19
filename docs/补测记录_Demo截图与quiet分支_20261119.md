# 补测记录 · Demo 截图 + quiet 分支（2026-11-19 21:40）

> 环境：Android 模拟器 `yindun30`（Android 11 / x86_64 / 1080×2280 / density 440）。
> **不是真机截图**，交付时必须在图注里写清环境（见第六节）。
> APK：`app/build/outputs/apk/debug/YinDun-debug.apk`（21:25 重编，含本次 TinyBert 修复）。

---

## 一、结论三条

| 项 | 结论 |
|---|---|
| 红屏拦截 | ✅ **实测通过**，截图入库 |
| 短信浮条 | ✅ **实测通过**，且与系统通知同屏，正好印证"放下方"的设计决策 |
| 分级行为 | ✅ `warn` 弹浮条、`watch` 只留痕不打扰（截图 2 / 3 对照） |
| quiet 分支 | ⚠️ **对陌生号永久不可达**，是死分支。代码 + 实测双证 |

另外顺手发现并修掉一个**会让整个守护进程崩掉**的 bug（第二节），这个比截图重要。

---

## 二、【P0】TinyBERT 在 x86 转译层下打死整个进程（已修）

### 现象

装 17:16 那版 APK 后启动，进程 8 秒内必死：

```
JNI DETECTED ERROR IN APPLICATION: attempt to access field boolean
  com.mindspore.Model.isModelSharePtr of type boolean with the wrong type int: 0x5f
  in call to GetIntField
  from java.util.List com.mindspore.Model.getInputs(long)
  at com.yindun.guard.TinyBert.doWarmUp(TinyBert.java:144)
  at com.yindun.guard.TinyBert.warmUp(TinyBert.java:101)
  at com.yindun.guard.GuardService$1.run(GuardService.java:91)
Fatal signal 6 (SIGABRT) in tid 8274 (tinybert-warmup)
→ Process com.yindun.guard (pid 8235) has died
→ Scheduling restart of crashed service ... in 1800000ms
```

`catch (Throwable)` 接不住 SIGABRT —— **不是"模型不可用"，是整个进程没了**，
守护服务要等系统 30 分钟才重启。这段时间里银行拦截、短信判定、振动全都没有。

### 根因

| 项 | 实测值 |
|---|---|
| `ro.product.cpu.abilist` | `x86_64,x86,arm64-v8a,armeabi-v7a,armeabi` |
| `ro.product.cpu.abi` | `x86_64` |
| `ro.enable.native.bridge.exec` | `1`（ARM 转译开启） |

`TinyBert.armSupported()` 原实现遍历整个 `SUPPORTED_ABIS`，看到末尾的 `arm64-v8a`
就返回 true —— 但那是模拟器转译层"能执行"的假象。于是它去加载官方 arm64 so，
经 `ndk_translation` 执行时 JNI 字段类型校验对不上（native 期望 `isModelSharePtr` 是 int，
Java 类里是 boolean），直接 SIGABRT。

**真机 arm64 不会踩到**（主 ABI 就是 arm64），所以 11-17 真机 9/9 全过没暴露。

### 修法

`app/src/com/yindun/guard/TinyBert.java` → `armSupported()`：先看**主 ABI**，
非 `arm*` 直接判不支持，不让它去碰 ARM 原生库。

### 修后实测

```
21:25:26.448 W YindunTinyBert: 端侧模型不可用：当前设备架构为 x86_64，
             MindSpore Lite 只提供 arm64-v8a / armeabi-v7a 原生库
21:25:26.448 I YinDun: 端侧模型未启用：当前设备架构为 x86_64 ...
```

进程存活，后续功能全部正常。真机 arm64 判定逻辑不变（`arm64-v8a`.startsWith("arm") = true）。

> **答辩口径**：这不是"模拟器不支持"，是**"模型加载失败必须降级，不能拖死守护进程"**。
> 原代码的 `catch (Throwable)` 本意就是"模型不可用不能让短信提醒跟着挂"，
> 但 SIGABRT 发生在 native 层，catch 拦不住 —— 所以必须在**加载之前**就把不支持的架构挡掉。

---

## 三、红屏拦截（截图 1）

复现步骤（模拟器）：

```bash
adb emu gsm call 13800138000 && adb emu gsm accept 13800138000   # 造通话中，mCallState=2
adb shell am start -n com.icbc.mobile/.MainActivity              # 打开假银行
```

日志（21:25:55）：

```
正在通话中，本次不硬拨，改记下来等挂断后补拨 18183254040
阻断判定，屏幕亮着（她看得见盖屏）
推给后端 /api/alert 结果 200
屏幕已盖住
持续振动已开始（振 400 / 停 1200，循环），最长 120 秒
屏幕抖动已开始（与振动同节奏：400ms 振 / 1200ms 停）
播放家人录音（本地缓存）：family.wav，来自 女儿
请盘古解释：接到陌生号码来电，已通话0分钟，打开了手机银行，通话中打开银行
6 秒无人操作，自动拨给子女
```

截图内容：深红全屏，`银盾 · 风险拦截`、`风险等级 4 / 4`、大字`暂停转账`、
`当前通话疑似诈骗`、`138 0013 8000 · 不在通讯录里`、主按钮`挂断这通，就拨给女儿`、
`已排队，挂了这通就拨`、底部`挂断这通电话` / `停止振动`。

文件：`docs/img/demo/01_红屏拦截_通话中打开银行.png`

---

## 四、短信浮条（截图 2）

```bash
adb emu sms send 10690555667788 "【邮政银行】您的储蓄卡在异地消费9860元，若非本人操作请立即致电客服95080或登录 http://psbc-verify.top 冻结卡片。"
```

日志（21:28:02）：

```
短信 from=10690555667788 who=service 分数=55 规则分=55 阶段=S2 级别=warn
  命中=冻结(+25)、客服(+10)、含链接(+20)
轻提醒已弹出（warn）：疑似诈骗短信
```

截图同屏两条：**顶部**是系统 Messages 通知（带 Mark as read / Reply），
**底部**是银盾浮条「疑似诈骗短信 / 请勿点击短信中的链接」（深色卡片 + 橙色描边 + 警告图标）。

这张恰好把 `SmsReceiver` 里的设计决策拍下来了 —— 注释写的就是
「我们的浮条如果也在顶部，就被整条压住……改成贴屏幕下方，两边互不干扰」。
两条同框是最直观的证据。

文件：`docs/img/demo/02_短信浮条_系统通知+银盾同屏.png`

### 顺带记录一个真实存在的节流

```
同类浮条 60 秒内已弹过，本次不再打扰老人（仍已上报）
```

60 秒内同类短信只弹一次浮条，但**照常上报云端**。这是设计行为，不是 bug。
补图时如果连发两条会被它挡掉，要么等 60 秒，要么重启进程。

### 补一张：watch 级不弹浮条（截图 3）

同一批测试里发了一条零命中词的陌生号短信（`我是你女儿的男朋友……`），
本地判 `15 分 / S1 / watch`，**没有弹浮条** —— 屏幕只有系统自己的短信通知。

这印证了分级行为：`none`/`watch` 只留痕不打扰，`warn` 才弹浮条给老人。
截图 2（warn）与截图 3（watch）放一起，就是"分级"这条设计最直接的证据。

文件：`docs/img/demo/03_本地watch级_不弹浮条.png`

---

## 五、quiet 分支：**对陌生号永久不可达**

### 原本想验证什么

`SmsReceiver.java:563`：

```java
boolean quiet = "none".equals(localLevel);
if (!quiet) EventBus.pushAlert("warn", "AI 复审：" + reason, stage, -1);
else        Log.i(TAG, "本地判 none、模型说可疑 —— 只推子女，不弹老人");
```

设计意图（注释原文）：本地毫无把握时只把线索推给子女，不打扰老人 ——
因为老人被误报烦几次就会去关悬浮窗权限，而那正是红屏依赖的同一个权限。

### 为什么不可达

| 环节 | 代码 / 配置 | 结果 |
|---|---|---|
| 陌生号加分 | `Judge.java:381` `if ("stranger".equals(who)) score += 15` | **无条件 +15** |
| 阶段门槛 | `rules.json` `stages.S1.need = 15` | 15 分即进 S1 |
| 级别 | `Judge.java:824` S1 → `score>=45 ? warn : watch` | **至少 watch** |
| 判据 | `"none".equals(localLevel)` | **永远 false** |

实测佐证（21:32:21，一条零命中词的陌生号短信）：

```
短信 from=13900008888 who=stranger 分数=15 规则分=15 阶段=S1 级别=watch
  命中=陌生号码(+15)
```

**任何陌生号短信，分数下限就是 15，级别下限就是 watch，quiet 永远不成立。**

### 那什么情况才可达

只有 `who=contact`（通讯录号码）且零命中（分数 0，`stageOf` 回 S0）→ level `none`
→ 送审（≥15 字即可，不要求陌生）→ 模型判可疑 → quiet 成立。

也就是**只有骗子把来电号码伪装成通讯录里的号码**（改号 / 伪基站）才走得到这条分支。
设计初衷那种「快递取件、社区体检通知」的场景，走的是陌生号，**根本进不了 quiet**，
只会 `pushAlert` + 弹浮条 —— 与注释里写的"不打扰老人"正好相反。

### 建议（**需要产品决策，没擅自改**）

两条路，二选一：

- **A. 放宽判据**：`quiet = levelRank(localLevel) <= rankOf("watch")`
  让"只在 watch 级"也走安静路径。改动一行，但与"watch 该不该打扰老人"的产品取舍绑定。
- **B. 承认死分支**：删掉 `quiet` 与那段注释，如实写"本地有把握（≥watch）就弹，没把握不弹"，
  避免代码里留一段永远不执行的分支骗自己。

我倾向 A —— 因为注释描述的行为（不打扰老人）是对的，是判据写窄了。

### 这次没能在设备上跑到"模型说可疑"那一步

模拟器 **DNS 解析不了域名**：

```
AI复审失败：域名解析失败：手机没有网络，或者地址写错了。
adb shell ping api.modelarts-maas.com  → unknown host
adb shell ping 8.8.8.8                  → 通
宿主 nslookup api.modelarts-maas.com    → 1.95.59.212 / 1.95.81.128（正常）
```

IP 层通、DNS 空。模拟器 `net.dns1` 为空，`ndc resolver` 不支持，`/system` 只读改不了 hosts，
`adb remount` 报 `system_ext: Read-only file system`。**这是模拟器的环境限制，不是 App 的问题**
（同一台模拟器能连 `123.249.25.139:8080`，日志里 `/api/alert 结果 200` 一直正常）。

结论不依赖这次模型调用 —— quiet 的前提 `localLevel=="none"` 在陌生号上恒为假，
模型答什么都不改变结果。

---

## 六、使用这些截图时的注意事项（别踩自己的铁律）

1. **图注必须写环境**：`Android 11 模拟器（x86_64）`，不能当"真机实拍"发出去。
2. 界面代码与 11-17 真机 9/9 验证的是同一条路径；本次唯一改动是 TinyBert 的架构判断，
   **不影响任何 UI**。
3. 假银行壳 `com.icbc.mobile` 是演示替身，页面底部自带`演示样例·非真实银行应用`，
   截图里能看到，不用另作说明。
4. `docs/img/demo/` 下只有这两张是**今天新截的**；
   `D:/华为ict/演示截图/` 里那 6 张是 **11-05 旧版界面**，与当前 UI 不一致，**不要混用**。

---

## 七、代码改动清单

| 文件 | 改动 | 影响 |
|---|---|---|
| `app/src/com/yindun/guard/TinyBert.java` | `armSupported()` 增加主 ABI 判断 | 修 P0 崩溃；真机行为不变 |

`quiet` 分支**未改**，等产品决策。
