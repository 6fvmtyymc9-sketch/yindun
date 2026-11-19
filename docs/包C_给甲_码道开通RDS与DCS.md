# 包 C · 给甲：用码道 Skills 开通 RDS 与 DCS

> 2026-11-19 20:00 · 执行人：同学甲 · 依据 `docs/任务分工.md` 第 103~140 行
> **目标**：赛题第 2 条「基于已有智能体和 Skills，进行关联 RDS、Redis 等华为云服务的快速开通创建」
> **一句话**：全程在码道里用 Skills 开，每步截图，开完把 IP 和密码交回来。

---

## 零、先看清楚：这次要交什么

| # | 交付物 | 存在哪 |
|---|---|---|
| 1 | 码道对话全程录屏（不剪） | 发我，我入库 |
| 2 | 6 个关键画面截图 | 发我，我放 `docs/img/codearts/` |
| 3 | 新 RDS 的内网地址、新 Redis 的内网地址 | 微信发我 |
| 4 | 两个实例的密码 | 微信发我，**不要贴群** |

**不要自己迁数据、不要改 `.env`、不要删任何实例。** 开完就停，剩下的我来。

---

## 一、前置自检（5 分钟，不通就停，别往下走）

打开**码道的终端**（不是 Windows CMD），依次跑：

```bash
node -v
npx --yes huaweicloud-devkit@latest doctor
```

**判断标准**：
- `node -v` 要 **≥ v22.19**。低于这个版本，停下告诉我。
- `doctor` 的输出里，只要没有 `[FAIL]` 就可以继续；有 `[FAIL]` **把整段输出截图发我**，不要自己改参数重试。

第一次跑 `doctor` 会有多个 FAIL 是正常的（还没装）。装完再跑一次才是准的。

---

## 二、装 Skills 和 KooCLI（2 分钟）

在码道终端里跑这两条，一条一条来：

```bash
npx --yes huaweicloud-devkit@latest install --target codearts
```

```bash
npx --yes huaweicloud-devkit@latest install-hcloud
```

装完再跑一次自检：

```bash
npx --yes huaweicloud-devkit@latest doctor
```

**期望结果**：`Results: 10 pass, 1 warn, 0 fail`
那条 WARN 是「检测到码道沙箱模式」，下一步解决。

**这一步的截图**：`doctor` 的完整输出（能看清 `Skills installed (29)`）。

---

## 三、关沙箱 + 配凭据（2 分钟）

### 3.1 关沙箱（必须）

码道 → **设置 → 对话流 → 智能体 → 终端命令运行模式 → 选「自动运行」**

不关的后果：KooCLI 写不进 `~/.hcloud/`，会卡在隐私协议上，Skill 调不动云 API。

### 3.2 配 AK/SK

AK/SK 我会单独微信发你，**不要写进任何文件、不要提交进仓库**。

在码道终端跑（把 `你的AK` `你的SK` 换成我发的）：

```bash
hcloud configure set --cli-access-key=你的AK --cli-secret-key=你的SK --cli-region=cn-north-4 --cli-profile=default
```

跑完验证，这条必须通：

```bash
hcloud RDS ListInstances --cli-region=cn-north-4
```

**期望**：返回 JSON，里面能看到 `yindun-demo-1119`。
看不到就停，把报错发我。

**注意**：这里用的是**队长的账号**，不是你自己的。原因见第七节红线第 1 条。

### 3.3 重启会话

关掉当前码道对话窗口，**重新开一个**。MCP 工具只在新会话生效。

**怎么确认生效**：新会话里让码道「列出你可用的工具」，应该能看到 `huaweicloud_*` 开头的（比如 `huaweicloud_check_cli`）。

---

## 四、开录屏，贴指令

**先开录屏再说话**，从你输入指令那一刻开始录，中间不要暂停。

录屏工具随意：Windows 用 `Win + G`，或者用手机拍屏幕。**要求能看清字**。

### 4.1 贴这段（原样复制，不要改）

```
用 huawei-rds 和 huawei-cloud-dds-dcs-instance-management 两个 Skill，
在 cn-north-4 帮我开通两个云服务实例，参数我已经查好了，请严格按这些参数执行：

【RDS for MySQL】
- 实例名：yindun-rds-1120
- 引擎：MySQL 8.0，单机版（ha mode = single）
- 规格：rds.mysql.x1.large.2（2核4G）
- 存储：CLOUDSSD，40G
- 可用区：cn-north-4a
- 端口：3306
- 密码：Yd2026Skill#a7
- 计费：按需（postPaid）
- VPC ID：8f452f90-f5bd-4b51-ad48-4eb1d101552f
- 子网 ID：9bb53754-4a4c-4e00-b368-61059c9fbb9e
- 安全组 ID：7a3bcc1a-9a6f-4119-b7f9-397a03fca4f7
- region 参数要和 cli-region 一致，都填 cn-north-4

【DCS Redis】
- 实例名：yindun-redis-1120
- 引擎：Redis 6.0，单机
- 规格：redis.single.xu1.large.1（1G）
- 可用区参数用 az_codes，值 ["cn-north-4a"]
- 端口：6379
- 密码：Yd2026Skill#b8
- 计费：按需（postPaid）
- VPC / 子网 / 安全组 ID 同上

要求：
1. 先用 ListInstances 看现有实例，确认这两个名字没被占用；
2. 把每一步执行的完整命令和返回结果都打印出来，不要省略；
3. 创建完成后，用 ListInstances 查出这两个实例的 ID 和内网地址（private_ip）列给我；
4. 如果报错，把错误码和你的判断一起说一下，然后修正再试。
```

### 4.2 它会怎么走（你按这个核对，走偏了就打断）

1. 加载 `huawei-rds` 和 `huawei-cloud-dds-dcs-instance-management`
2. `ListInstances` 查重名 → 打印结果
3. `ListFlavors` 核对规格编码（这一屏很重要，能证明参数不是我硬塞的）
4. `CreateInstance` 创建 RDS → 返回实例 ID
5. 同样的流程创建 Redis
6. `ListInstances` 复查，给出内网 IP

**中间出现报错是正常的，不要停录屏。** 它自己改参数重试的过程，恰恰是「意图理解 + 任务规划」最有说服力的部分。只要最后两个实例都出来了就行。

---

## 五、6 个必拍画面（少一个说服力降一半）

| # | 拍什么 | 为什么 |
|---|---|---|
| 1 | 你贴的那段自然语言指令（原文可见） | 证明起点是"人话"，不是脚本 |
| 2 | 码道的**任务规划**（它把活拆成几步） | 赛题第 2 条要的"意图理解和任务规划" |
| 3 | **Skill 被加载**（界面出现 `huawei-rds` / `dds-dcs` 的名字） | 证明真的在用 Skills，不是它自己瞎编 |
| 4 | `ListFlavors` / `ListInstances` 的查询结果 | 证明参数是查出来的 |
| 5 | `CreateInstance` 提交后状态 `BUILD` / `CREATING` | 证明"创建"这个动作 |
| 6 | 复查时 `yindun-rds-1120` 和 `yindun-redis-1120` 都 `ACTIVE` / `RUNNING`，**带实例 ID 和内网 IP** | 最终证据 |

---

## 六、开完把什么交回来

微信发我这四项：

```
1. RDS 内网地址：192.168.0.___:3306
2. Redis 内网地址：192.168.0.___:6379
3. 两个实例 ID
4. 录屏 + 截图
```

**然后你就没事了**，别动线上任何东西。

---

## 七、红线（每条都是踩过的坑）

**1. AK/SK 必须是队长那套，不能是你自己的**
你俩账号各有一个叫 `vpc-default` 的 VPC，ID 不同（队长 `8f452f90`、你的 `b1b68207`）。
ECS 在队长的 VPC 里。**用你自己的 AK 开，实例落在你的 VPC，内网永远不通**，
而且 RDS 的 VPC **创建后不能改**，只能删了重开——一小时的活变三小时。

**2. VPC / 子网必须按 ID 核对，不能按名字选**
两个账号都有 `vpc-default`。名字对不上不要紧，ID 必须是 `8f452f90-f5bd-4b51-ad48-4eb1d101552f`。

**3. 安全组必须用 `7a3bcc1a-9a6f-4119-b7f9-397a03fca4f7`（Sys-WebServer）**
新建安全组忘了放行 ECS 内网 IP，会出现"实例开好了、IP 也有、就是连不上"，
报错是连接超时，看不出原因。

**4. 规格不要改**
RDS 用 `rds.mysql.x1.large.2`（2核4G）、Redis 用 `redis.single.xu1.large.1`（1G）。
别为了省钱降到 1 核 2G，跑融合判定 + 写库会慢，演示时卡。

**5. 密码照抄**
RDS `Yd2026Skill#a7`、Redis `Yd2026Skill#b8`。改了我这边连不上，得重开。

**6. 只开不删、只开不迁**
迁移和切换我来做。你删了任何实例，线上就断。

**7. 不要提交任何含 AK/SK 的文件**
码道的 MCP 配置里会有 `~/.hcloud/config.json`，那是本机配置，**不要拷进仓库**。

---

## 八、卡住了怎么办

**按顺序做这三件，别自己硬试：**

1. 把 `doctor` 完整输出发我
2. 把码道里那段报错原文发我（截图，不要转述）
3. 停下等我回话

**千万不要做的事**：
- 换规格重试（会开出一堆实例，都要钱）
- 换可用区重试（如果不是 `az_codes` 参数名问题，换哪个都一样错）
- 用自己账号重试（VPC 错了，重试一百次也不通）

---

## 九、参数速查（我给码道那段话里的，这里是完整版备查）

| 项 | RDS | Redis |
|---|---|---|
| 区域 | cn-north-4 | cn-north-4 |
| 可用区 | cn-north-4a | cn-north-4a（参数名 `az_codes`） |
| 规格 | `rds.mysql.x1.large.2` | `redis.single.xu1.large.1` |
| 引擎版本 | MySQL 8.0 | Redis 6.0 |
| 存储 | CLOUDSSD 40G | — |
| 端口 | 3306 | 6379 |
| 计费 | postPaid | postPaid |
| VPC ID | `8f452f90-f5bd-4b51-ad48-4eb1d101552f` | 同左 |
| 子网 ID | `9bb53754-4a4c-4e00-b368-61059c9fbb9e` | 同左 |
| 安全组 ID | `7a3bcc1a-9a6f-4119-b7f9-397a03fca4f7` | 同左 |
| 实例名 | `yindun-rds-1120` | `yindun-redis-1120` |

**费用**：RDS 0.40 元/时 + Redis 0.29 元/时 = **0.69 元/时**。开完等我来迁，迁完当天就删旧的。

---

## 十、这件事在计划里的位置

`docs/任务分工.md` 第 109 行原文：

> **全程通过 CodeArts 智能体 + Skills 操作**，每步截图存 `docs/img/codearts/`
> （这是赛题第 2 条的唯一证据，不是走形式）

所以录屏和截图不是附加项，**是这条任务的全部产出**。实例反而是副产物。

现在线上挂的是我之前用 API 直开的那组（能跑，但过程不合规，所以赛题第 2 条现在判"未满足"）。
你这次开出来的两个，迁完数据后**会成为线上真正在用的那组**，旧的当天释放。
这样评委问"线上这个 RDS 是不是 Skills 开的"，答案就是"是"。
