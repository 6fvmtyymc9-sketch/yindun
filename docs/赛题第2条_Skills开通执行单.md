# 赛题第 2 条 · 用码道 Skills 开通 RDS/DCS 执行单

> ⛔ **本文已作废（2026-11-19 20:00）。包 C 按计划由同学甲执行，请看
> [`包C_给甲_码道开通RDS与DCS.md`](./包C_给甲_码道开通RDS与DCS.md)。**
> 本文保留只为记录「环境是怎么铺起来的」这一段，不要照着它执行。

> 2026-11-19 19:45 · 环境已全部铺好，只剩「在码道里说一句话并录屏」这一步要人做

---

## 一、赛题原文（三条任务要求，一字不改）

1. 基于已有智能体和 Skills，**进行用户意图理解和任务规划**；
2. 基于已有智能体和 Skills，**进行关联 RDS、Redis 等华为云服务的快速开通创建**；
3. 基于已有智能体和 Skills，**进行应用作品在华为云上的快速部署**。

**验收看的是"过程"：谁在规划、谁在调 Skills、Skills 调了什么。**
不是看"云上有没有实例"。

---

## 二、我们此前的偏差（如实记录）

2026-11-19 19:30 我用华为云 API（自写 `HwSign.js` 签名 + 直调 REST）
把 RDS 与 DCS 开出来了，数据也迁了、`.env` 也切了。

**技术上是真的，但路径不对**：码道没有参与，智能体没有规划，Skills 没有被调用。
按赛题第 2 条的措辞，这条**不算满足**。

补救办法不是"把现有实例说成 Skills 开的"（那是事实性不准确，评委一查账号和创建时间就露），
而是**真的用 Skills 再开一组，并且让新的这组成为线上跑的那组**——过程与结果合一，
这样任何一个评委追问"线上这个是不是 Skills 开的"，答案都是"是"。

---

## 三、环境已就绪（2026-11-19 19:44 实测，不是我说的，是自检结果）

```
[PASS] Node.js >= 22.19
[PASS] MCP server installed
[PASS] MCP server can start (CodeArts)
[PASS] Runtime deps (undici) installed
[PASS] Safety policy installed
[PASS] Safety hook runtime (Node)
[PASS] MCP configured
[PASS] hcloud CLI installed
[PASS] hcloud credentials configured
[PASS] Skills installed (29)
[WARN] 检测到码道沙箱模式 (bash_mode: sandbox)
Results: 10 pass, 1 warn, 0 fail
```

已装的东西（全部在 `C:\Users\HUAWEI\.codeartsdoer\`）：

| 项 | 位置 / 版本 |
|---|---|
| 华为云 Skills（29 个） | `~/.codeartsdoer/skills/` |
| └ RDS 实例管理 | `huawei-rds`（含 `CreateInstance`） |
| └ DCS/DDS 缓存管理 | `huawei-cloud-dds-dcs-instance-management`（含 DCS `CreateInstance`） |
| └ 部署任务 | `huawei-cloud-deployment-task-management`（赛题第 3 条用） |
| └ 成本门禁 | `huawei-iac`（创建前强制查余额 + 成本确认） |
| └ VPC/ECS/IAM | `huawei-vpc` / `huawei-ecs` / `huawei-iam` |
| MCP 服务 | `~/.codeartsdoer/mcp/mcp_settings.json` → `huaweicloud-devkit` |
| KooCLI | `C:\Users\HUAWEI\hcloud\hcloud.exe` v7.3.12，AK/SK 已配、已验证能列出实例 |

**KooCLI 验证命令与结果**（说明链路真的通，不是我配置完就完事）：

```
hcloud RDS ListInstances --cli-region=cn-north-4
→ 返回 yindun-demo-1119 / ACTIVE / MySQL 8.0 / 40G
```

---

## 四、你要做的三步（约 5 分钟，含录屏）

### 第 1 步：关掉码道沙箱（30 秒，必须做）

码道 → **设置 → 对话流 → 智能体 → 终端命令运行模式 → 选「自动运行」**

不关的后果：KooCLI 无法写 `~/.hcloud/`，会卡在隐私协议确认，Skill 调不动云 API。

### 第 2 步：重启码道会话（10 秒）

MCP 工具只在**新会话**里生效。关掉当前对话窗口，重新开一个。

判断是否生效：新会话的可用工具里应能看到 `huaweicloud_*` 系列（如 `huaweicloud_check_cli`）。

### 第 3 步：开录屏，把下面这段原话贴进码道

> **录屏要包含：你的输入 → 智能体的规划 → Skill 加载 → 命令 → 结果**，一条不断。

```
用 huawei-rds 和 huawei-cloud-dds-dcs-instance-management 两个 Skill，
在 cn-north-4 开通两个云服务实例：

1) RDS for MySQL 8.0，单机版，2核4G，40G SSD，实例名 yindun-rds-skill，
   密码 Yd2026Skill#a7，VPC 用 vpc-default，子网 subnet-default，
   安全组 Sys-WebServer，端口 3306；
2) DCS Redis 6.0，单机版，1G，实例名 yindun-redis-skill，
   密码 Yd2026Skill#b8，VPC/子网/安全组同上。

要求：先用 ListFlavors 查出准确的规格编码，用 ListInstances 看现有实例避免重名，
把每一步的完整命令和返回结果都打出来，创建完把实例 ID 和内网地址列给我。
```

**不要把 AK/SK 贴进对话**——KooCLI 已配好，Skill 会自己用。

---

## 五、录屏必须拍到的 6 个画面（少一个说服力就降一半）

1. 你输入的这段自然语言（原文可见）
2. 智能体的**任务规划**（它把任务拆成几步）
3. **Skill 被加载**（界面上出现 `huawei-rds` / `dds-dcs` 的名字）
4. `ListFlavors` / `ListInstances` 的**查询结果**（证明它不是抄我给的参数）
5. `CreateInstance` 提交后实例状态 `BUILD` / `CREATING`
6. `ListInstances` 里出现 `yindun-rds-skill` 与 `yindun-redis-skill`，状态 `ACTIVE` / `RUNNING`，**带实例 ID**

---

## 六、创建成功后，剩下的由我做（你不用管）

1. 从 Skills 创建的新 RDS 里建 `yindun` 库、导入六张表，逐表 `COUNT(*)` 对数
2. 把线上 `.env` 的 `RDS_HOST` / `REDIS_URL` 切到新实例，重启并验证 `ss -tnp` 真实连接
3. 外网冒烟一条诈骗短信，确认 `isFraud:true`
4. **释放**我用 API 开的那两个旧实例（`yindun-demo-1119` / `yindun-redis-1119`），停止计费
5. 更新 `docs/RDS_DCS_开通记录_20261119.md` 与赛题符合性自查表，第 2 条改判为 **满足**

**这样最终形态是：线上跑的 RDS 和 Redis，就是码道 Skills 创建的那两个。**
不存在"演示用一套、线上用另一套"的问题。

---

## 七、两条铁律

1. **不要拿现有实例当 Skills 的成果写进材料。** 创建时间、创建方式都能被查出来，
   一旦被追问就是事实性不准确，比"没做"更糟。
2. **创建过程必须一次录完，不要剪。** 中间有失败重试也没关系，
   智能体遇到错误码再自己修正，反而是"意图理解 + 任务规划"最有说服力的部分。

---

## 八、顺带（赛题第 3 条）

`huawei-cloud-deployment-task-management` 也装好了。等第 2 条录完、
实例切换完，用同样方式让码道把后端部署再走一遍（哪怕服务已经在跑），
第 3 条一起补齐。这个不急，先把第 2 条拿下来。

---

## 附：代金券

DevKit 安装时提示：**首次使用插件可领取一张华为云代金券（一人一次）**。
在码道里说「帮我领取一张华为云代金券」即可，`huawei-voucher` Skill 会处理。
