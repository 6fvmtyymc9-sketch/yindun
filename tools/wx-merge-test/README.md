# 微信合并通知剥离逻辑 · 回归用例

验证 `NotiGuard.stripMergeWrapper()`：微信连发多条时，通知正文变成
`[3条]-zzz～y：4.原话`，这段逻辑负责把包装剥掉、只留原话去判。

## 为什么要有这个用例

2026-11-16 曾加过一条"多条合并一律跳过"的分支，前提是"合并时正文没有真内容"。
真机（Honor 7X / EMUI / Android 9）实测推翻了这个前提——前缀后面就是原话。
整条跳过的后果是**连发第二条起彻底静默**，2026-11-21 由用户真机实测发现。

详见 `docs/微信合并通知漏报修复_20261121.md`。

## 怎么跑

不需要 Android SDK，纯字符串逻辑：

```bash
cd tools/wx-merge-test
"D:/Java/bin/javac.exe" -encoding UTF-8 -d . WxMergeStripTest.java
"D:/Java/bin/java.exe" -Dfile.encoding=UTF-8 WxMergeStripTest
```

期望输出：`==== 12 pass, 0 fail ====`

⚠️ 这个文件是 `NotiGuard.stripMergeWrapper()` 的**副本**，改了实现要同步改这里，
否则用例会变成"测的另一份代码"。
