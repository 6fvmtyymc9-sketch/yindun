# 银盾 · 老人反诈守护系统

华为 ICT 大赛创新赛道参赛作品 —— 云端判定与调度服务（B 线）

---

## 一句话

老人接诈骗电话时，手机上一出现"要动钱"的动作，立刻盖屏，同时让子女知道。
**全程不读取任何通话内容** —— 这是设计的核心，也是合规的底线。

---

## 架构

```
       安卓端（B线采集）                 云端（本仓库）              子女端
 ┌──────────────────────┐      ┌──────────────────────────┐   ┌──────────┐
 │ 通话状态 / 前台应用   │ POST │  Judge  规则打分          │   │ 预警列表 │
 │ 短信到达 / 装包行为   │─────▶│    ↓                     │──▶│ SSE 实时 │
 │                      │      │  Guard  判级 → 去重 → 干预 │   │ AI 摘要  │
 │ 决策：L1 本地规则     │◀─────│    ↓                     │   └──────────┘
 │ 盖屏 / 遮验证码       │ 动作 │  Redis 实时态 + RDS 事件   │
 └──────────────────────┘      │  MaaS 灰区复审（可选）     │
                               └──────────────────────────┘
```

**分层原则**：盖屏是**毫秒级本地判断**，断网也照常工作；
云端模型只在"灰区复审"和"给子女生成一句人话摘要"时参与，超时就跳过，
**永不参与拦截决策**。

---

## 目录

```
cloud/
├── src/
│   ├── server.js    服务入口：路由、静态页、SSE 推送
│   ├── Judge.js     判定引擎：读 rules.json 打分、判阶段、判等级
│   ├── Guard.js     调度器：事件 → 状态 → 判级 → 去重 → 干预
│   ├── State.js     会话状态（Redis，连不上自动降级内存）
│   ├── Db.js        数据访问（RDS MySQL，连不上自动降级内存）
│   ├── Pangu.js     华为云 MaaS 调用（灰区复审 + 人话摘要 + 成本记账）
│   └── env.js       轻量 .env 加载器（零第三方依赖）
├── config/
│   └── rules.json   规则表（改完热更新，不用重新部署）
├── sql/
│   └── schema.sql   建表脚本
├── public/
│   └── index.html   演示页面（老人端 + 子女端，已接真实接口）
├── test/
│   ├── run.js       判定引擎回归测试（23 条用例）
│   ├── counter.js   反例误报测试（5 个场景，必须全部不报警）
│   └── probe/       联调期的一次性探测脚本
├── verify_maas.js   模型实测报账单（token / 耗时 / 花费）
├── demo.js          完整诈骗流程演练脚本
├── .env.example     配置模板
└── package.json
```

---

## 本地跑起来

```bash
npm install
cp .env.example .env     # 不想接模型就留空，完全能跑
node src/server.js
```

打开 http://localhost:8080

**不带任何环境变量也能跑** —— Redis / RDS / MaaS 全部降级，
保证演示现场断网、账号没开通的情况下也能完整走完流程。

---

## 配置真实服务

编辑 `.env`（模板见 `.env.example`）：

```ini
# 华为云 MaaS（OpenAI 兼容接口）
MAAS_URL=https://api.modelarts-maas.com/v2/chat/completions
MAAS_KEY=你的API Key
MAAS_MODEL=deepseek-v4.1-flash
MAAS_TIMEOUT=20000

# Redis（华为云 DCS）
REDIS_URL=redis://user:密码@host:6379/0

# RDS for MySQL
RDS_HOST=xxx.rds.cn-north-4.myhuaweicloud.com
RDS_PORT=3306
RDS_USER=root
RDS_PASS=密码
RDS_DB=yindun
```

启动日志会明确告诉你哪些依赖连上了、哪些降级了。

**模型选型说明**：DeepSeek-V4.1-Flash 属非思考型模型，
调用时带 `thinking:{type:'disabled'}` 跳过思维链，实测**平均 0.64 秒**返回；
单次复审约 170 tokens，成本约 **¥0.00034**（十万分之三块钱）。
`verify_maas.js` 会打印每次调用的真实 token 数与扣费。

---

## 接口

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/health` | 健康检查，一眼看出各依赖状态与当前模型 |
| POST | `/api/event` | **事件上报，整个系统的唯一入口** |
| GET | `/api/stream?elderId=` | 子女端实时订阅（SSE） |
| GET | `/api/alerts?elderId=` | 子女端历史预警 |
| POST | `/api/known` | 熟人标记（只免打扰 15 分钟，不关防护） |
| POST | `/api/reset` | 重置一场演示 |
| POST | `/api/rules` | 规则热更新 |
| GET | `/api/pangu/ping` | 模型连通性自检 |
| POST | `/api/report` | **一键上传可疑短信**（截图或文字）→ 判定阶段与结论 |
| GET | `/api/report/stats?number=` | 号码信誉：被几人举报过，≥3 即标红 |
| GET | `/api/reports` | 最近的上报记录 |
| GET | `/api/drill/scenarios` | 经典剧本列表（4 套写死的，断网也能演） |
| GET | `/api/drill/topics` | **热点库**：近期高发的诈骗类型，带热度权重 |
| GET | `/api/drill/cases` | **案例库**：上传过的诈骗信息，已脱敏，可做演练种子 |
| POST | `/api/drill/start` | 开一局演练，支持 `mode` = `hot`/`case`/`mix`/`classic`/`auto` |
| POST | `/api/drill/turn` | 提交本轮选择，返回对错与下一步 |
| GET/POST | `/api/drill/plan` | 定期演练计划（每周 / 每两周 / 每月 / 关闭） |
| GET | `/api/drill/stats` | 演练历史、平均分、**最常失守的环节** |

### 演练与案例库怎么联动

演练不再是 4 个固定剧本。素材有两个来源，都先被拆成「骨架」再重新组装：

```
热点库（人工维护的热门骗术）─┐
                            ├─▶ 骨架(载体/身份/由头/施压/索要) ─▶ 变体 ─▶ 4 轮剧本
用户上传的诈骗信息（脱敏）──┘
```

- **换外壳不换套路**：一条"冒充公检法"的案例，能衍生出冒充社保局、银行风控、快递公司等变体，
  施压逻辑一模一样。老人学到的是套路，不是背台词。
- **跨套路混合**（`mode=mix`）：骗子真实打法是"一层壳建信任，另一层壳收割"，
  所以内置三种桥接范式——先服务后执法、先给甜头后收割、先制造恐慌再当救星、层层转接放大权威。
  桥接必须与两边的身份匹配，接不上就不混合，硬凑会演成四不像。
- **判分不交给模型**：正确选项由 `身份类别 × 阶段 × 索要方式` 查表得出，
  干扰项从同类池里抽。模型只负责把台词说人话。

一句话验证：

```bash
# 用你上传过的那条案例衍生一局
curl -s -X POST localhost:8080/api/drill/start \
  -H 'Content-Type: application/json' -d '{"mode":"case","reportId":1}' | head -c 400

# 跨套路混合：案例 + 本周热点叠加
curl -s -X POST localhost:8080/api/drill/start \
  -H 'Content-Type: application/json' -d '{"mode":"mix"}' | head -c 400
```

### 两个新增能力

**一键上传（功能②延伸）**
老人或子女把可疑短信截图贴上来，云端用多模态直接读图（不需要额外 OCR 服务），
返回 0~4 的阶段、诈骗类型、理由、给老人的一句建议。
实测：430×760 截图约 830 prompt tokens，延迟 ≈2.9 秒，花费 ≈¥0.0027。

**定期演练（功能④ 反诈疫苗）**
模型扮演骗子，按「套近乎 → 吓唬 → 隔离 → 要钱」四步推进，老人每轮做一次应对，
4 轮后出成绩单（≥75 及格），并指出错在哪一步。
AI 演练手机端是**原生页面**（`DrillActivity`），不是 WebView；提醒由 `AlarmManager` 每周一 10:00 拉起。
判分不交给模型——对错写在剧本里，模型只负责"说人话"，否则模型抽风把"转账"判成正确，这个功能就废了。

### 事件格式

```jsonc
// 来电
{"type":"call","state":"ringing","number":"+12025550137","who":"stranger"}
{"type":"call","state":"active","dur":320,"who":"stranger"}
{"type":"call","state":"end"}

// 短信
{"type":"sms","body":"...","who":"stranger","isCode":true}

// 前台应用
{"type":"app","pkg":"com.icbc","label":"中国工商银行","isBank":true}

// 装包 / 无障碍
{"type":"install","fromStore":false,"pkg":"com.bad.app"}
{"type":"a11y","granted":true}
```

`who` 三选一：`stranger` 陌生人 / `contact` 通讯录 / `service` 服务号

---

## 测试

```bash
node test/run.js       # 判定引擎：23 条用例，判错就回去改 rules.json
node test/counter.js   # 反例：5 个正常场景，必须一条都不报警
node demo.js           # 完整跑一场诈骗，看子女端收到几张卡
node verify_maas.js    # 模型实测：延迟 / token / 花费
```

**`counter.js` 比 `run.js` 更重要。**
漏报只是少抓一次，误报会让老人觉得被冤枉，然后卸载 App。
一次误报的代价，大于十次漏报。

---

## 部署到华为云 ECS

```bash
# 1. 一台 ECS（2核4G 够用），系统 Ubuntu 22.04，安全组放通 8080
ssh root@<公网IP>
apt update && apt install -y nodejs npm     # Node 需 ≥ 18

# 2. 传代码
scp -r cloud/ root@<公网IP>:/opt/yindun

# 3. 起服务
cd /opt/yindun
npm install --production
nohup node src/server.js > /var/log/yindun.log 2>&1 &
curl http://localhost:8080/api/health
```

浏览器打开 `http://<公网IP>:8080` 即演示页。

### 开机自启

```bash
cat > /etc/systemd/system/yindun.service <<'EOF'
[Unit]
Description=YinDun Guard Service
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/yindun
EnvironmentFile=/opt/yindun/.env
ExecStart=/usr/bin/node src/server.js
Restart=always

[Install]
WantedBy=multi-user.target
EOF

systemctl enable --now yindun
```

---

## 三条不能破的设计约束

**1. 不读通话内容**
安卓从 9 开始禁止第三方录通话，这条路上限为零。
所以所有判据都只看动作：开银行、收到验证码、装软件、通话时长。
代码里有禁止字段清单，规则里写 `call.content` 直接抛异常。

**2. 点"认识"不能关掉防护**
老人说"这个号码我认识"，只能让他 15 分钟内不再被问，
子女端照收每一条预警，之后动钱照样盖屏。
能让老人少受打扰的开关，绝不能同时是能关掉防护的开关。

**3. 本地规则决定拦不拦，模型只做补充**
盖屏是毫秒级本地判断，断网也能用。
模型只在灰区复审和生成人话摘要时参与，超时就跳过。
如果把拦不拦交给模型，网差多等 1~3 秒，而那时钱正在动。

---

## 许可证

仅用于华为 ICT 大赛参赛与评审。
