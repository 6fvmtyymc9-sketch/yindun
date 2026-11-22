# 系统技术架构图（材料用图）

- 文件：`系统架构图.png`（3000 × 1600，白底，可直接插入 Word 正文）
- 源文件：`arch_src.html`（内联 SVG，改文字/坐标后重渲染即可，不用 PS）
- 生成：2026-11-22

## 重渲染命令

```bash
cd "D:/华为ict/交付_银盾_GitHub/docs/img/arch"
"/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" --headless=new --disable-gpu \
  --hide-scrollbars --no-first-run --default-background-color=ffffffff \
  --force-device-scale-factor=2 --window-size=1500,800 \
  --screenshot="$PWD/系统架构图.png" "file:///$PWD/arch_src.html"
```

⚠️ 别加 `--virtual-time-budget`，会把 Edge 无头渲染器卡死（踩过两次）。

## 图注（插进材料时照抄，别改口径）

> 图 1　银盾系统技术架构：老人端（com.yindun.guard）与子女端（com.yindun.child）两个原生安卓 App，
> 中间经部署在华为云 ECS 上的 Node 18 + Express 服务通信。高风险判定 100% 在手机本地完成，
> 命中即盖屏、不等网络；云端只承担三件事：把告警实时推给子女、对灰区短信做模型复审、持久化历史记录。

## 图上每个数字的出处（被问时能答）

| 图上写 | 依据 |
|---|---|
| 220 特征词 + 33 反例 + 11 组合规则 | 规则表实际条数；`/api/health` 自报 `rules 3.6 / 220` |
| 四级 watch/warn/high/block，阈值 25/45/70/90 | `Judge.java` 分级常量 |
| 五档阶段 S0→S4（接触/信任/诱导/索款/转账） | `Judge.java` 阶段机 |
| 7 表持久化 | RDS 迁移复核「七表逐行一致」 |
| 大模型中位 42ms / P90 1.17s / ¥0.00091 条 | 1,079 条真实请求实测（非估算） |
| SSE 推送中位 67ms | POST→SSE 实测，不含 4G 上行 |
| 3 秒重连回填 | 子女端断线重连策略 |

## 两条必须守住的表述

1. **「断网可用」指本地判定引擎**，不是整个 App 断网全功能可用——断网时子女端收不到推送，
   这一点别在正文里说反。
2. **「可一键回拨」指子女端详情页的「致电妈妈」按钮**，是子女端发起；
   老人端的「打给女儿」是另一个动作，别混。

## 可选加图（本图未含）

若正文还想配图，建议只加 3 张，不要铺满：

| 用途 | 文件 |
|---|---|
| 真机拦截现场 | `../realdevice/rd02-call-block-red.jpg` |
| 真机微信可疑提示 | `../realdevice/rd01-wechat-warn.jpg` |
| 子女端预警列表 | `../demo/08-child-alert-list.png` |
