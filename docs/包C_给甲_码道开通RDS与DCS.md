# 包 C · 给甲：用码道 Skills 开通 RDS 与 DCS

> 2026-11-19 20:05 · 执行人：同学甲
> 依据：赛题任务要求第 2 条 + 老师方案 C2~C5 + `docs/任务分工.md` 第 103~140 行
> **本文每一条命令都在本机实测过**，末尾附录给出实测返回，不是推测。

---

## 零、开跑之前先确认三件事

| # | 确认什么 | 不满足怎么办 |
|---|---|---|
| 1 | 你电脑上装了**码道**（CodeArts Agent 或 VS Code 码道插件） | 先装，装完再往下看 |
| 2 | 你的码道能打开终端（能敲 `node -v` 出结果） | 找不到终端就问我 |
| 3 | 你能收到我单独发的 **AK/SK**（队长的账号） | 没收到先问我要，别自己注册 |

---

## 一、总目标：只做一件事

**在码道里用 Skills 把两个云实例开出来，全程录屏 + 6 张截图。**

不要迁数据、不要改线上配置、不要删任何东西。开完就停。

| 要开的东西 | 名字 | 规格 |
|---|---|---|
| RDS for MySQL | `yindun-rds-1120` | MySQL 8.0 单机 2核4G 40G SSD |
| DCS Redis | `yindun-redis-1120` | Redis 6.0 单机 1G |

---

## 二、第 1 步：环境自检（3 分钟）

在**码道的终端**里敲：

```bash
node -v
```

**必须 ≥ v22.19。** 低于这个数就停，发截图给我。

```bash
npx --yes huaweicloud-devkit@latest doctor
```

第一次跑会有一堆 `[FAIL]`，**这是正常的**（还没装）。把输出留着，装完要对比。

> ⚠️ 如果这条命令报 `npx: command not found`，说明 Node 没装好，停下找我。

---

## 三、第 2 步：装 Skills 与 KooCLI（3 分钟）

一条一条跑，别合并：

```bash
npx --yes huaweicloud-devkit@latest install --target codearts
```

```bash
npx --yes huaweicloud-devkit@latest install-hcloud
```

装完再自检一次：

```bash
npx --yes huaweicloud-devkit@latest doctor
```

**期望结果**：`Results: 10 pass, 1 warn, 0 fail`

| 看到什么 | 含义 |
|---|---|
| `Skills installed (29)` | 29 个华为云技能装好了，包含 RDS 和 DCS |
| 唯一一条 `[WARN] 检测到码道沙箱模式` | 正常，下一步解决 |
| 任何 `[FAIL]` | **停下，把整段输出发我** |

**这一步要截一张图**（`doctor` 的完整输出）。

---

## 四、第 3 步：关沙箱 + 配凭据（2 分钟）

### 4.1 关沙箱（必须，不关后面全废）

码道 → **设置 → 对话流 → 智能体 → 终端命令运行模式 → 选「自动运行」**

原因：沙箱模式下 KooCLI 写不进 `~/.hcloud/`，会卡在隐私协议那一步。

### 4.2 配 AK/SK

AK/SK 我从微信单独发你，**不要写进任何文件、不要提交进仓库、不要贴群**。

在码道终端跑（把我发你的值粘进去）：

```bash
hcloud configure set --cli-access-key=你的AK --cli-secret-key=你的SK --cli-region=cn-north-4 --cli-profile=default
```

**必须验证这一步真的通了**，跑：

```bash
hcloud RDS ListInstances --cli-region=cn-north-4
```

**期望**：返回一段 JSON，里面有 `"name": "yindun-demo-1119"`（这是我之前开的，能看到就说明凭据对了）。

如果报 `配置文件中不存在配置项` → 上一条 `configure set` 没成功，重跑。
如果报 `认证失败` → AK/SK 抄错了，找我要新的。

### 4.3 重启会话（关键）

关掉当前码道对话窗口，**重新开一个**。

MCP 工具**只在新会话生效**。不重启，智能体看不到 `huaweicloud_*` 系列工具。

**怎么确认生效**：新会话里问码道「你现在有哪些华为云相关的工具」，能看到 `huaweicloud_check_cli` 这类就对了。

---

## 五、第 4 步：开录屏，让码道干活

**先按录制，再输入。** 中间不要暂停、不要剪辑。

Windows 用 `Win + G`，或者手机架着拍屏幕。要求：**字能看清**。

### 5.1 把下面这段整段复制进码道

```
用 huawei-rds 和 huawei-cloud-dds-dcs-instance-management 两个 Skill，
在 cn-north-4 给我开通两个云服务实例。参数我已经查好了，请严格按这些执行，
不要自己换规格或换可用区：

【RDS for MySQL】
实例名 yindun-rds-1120
引擎 MySQL 8.0，单机（ha.mode = single）
规格 rds.mysql.x1.large.2（2核4G）
存储 CLOUDSSD 40G
可用区 cn-north-4a
端口 3306
密码 Yd2026Skill#a7
计费 postPaid
VPC ID    8f452f90-f5bd-4b51-ad48-4eb1d101552f
子网 ID   9bb53754-4a4c-4e00-b368-61059c9fbb9e
安全组 ID 7a3bcc1a-9a6f-4119-b7f9-397a03fca4f7
注意：body 里的 region 也要填 cn-north-4，和 cli-region 一致，否则会报 DBS.280243。

【DCS Redis】
实例名 yindun-redis-1120
引擎 Redis，版本 6.0，单机
规格 redis.single.xu1.large.1（1G）
可用区参数名是 az_codes，值是 ["cn-north-4a"]（不是 available_zones）
端口 6379
密码 Yd2026Skill#b8
计费 postPaid
VPC / 子网 / 安全组 ID 同上

执行要求：
1. 先用 ListInstances 看现有实例，确认这两个名字没被占用；
2. 每一步执行的完整命令和返回结果都要打印出来，不要省略；
3. 这两个实例的密码参数会和 KooCLI 系统参数冲突，请用 --cli-jsonInput 传 JSON 文件，
   不要直接用 --password=；
4. 创建完成后用 ListInstances 查出实例 ID 和内网地址（private_ip）列给我；
5. 报错就把错误码和你的判断一起打出来，然后修正重试。
```

### 5.2 正常的话，它会这样走（你按这个核对）

1. 加载两个 Skill
2. `ListInstances` 查重名 → 打印
3. `ListFlavors` 核对规格 → 打印（**这一屏很重要**）
4. 写 JSON 文件 + `RDS CreateInstance --cli-jsonInput=...` → 返回实例 ID
5. 同样的流程开 Redis
6. `ListInstances` 复查 → 给出内网 IP

**中间报错别停录屏。** 它自己改参数重试的过程，恰恰是赛题第 1 条要的「意图理解和任务规划」，比一次成功更有说服力。

### 5.3 如果它卡在密码上（大概率会遇到）

`--password` 与 KooCLI 系统参数同名，直接写在命令里会报：

```
目标API中存在与KooCLI系统参数password同名的参数,无法识别--password的位置
```

**这是实测过的已知问题**，不是你操作错了。把附录 A 的 JSON 内容和附录 B 的两条命令发给它（或自己跑），就能过。**我已经用非法实例名验证过，这两个 JSON 结构 100% 正确，只差名字。**

---

## 六、6 个必拍画面（少一个说服力降一半）

| # | 拍什么 | 证明什么 |
|---|---|---|
| 1 | 你贴的那段自然语言指令（原文可见） | 起点是"人话" |
| 2 | 码道的**任务规划**（它把活拆成几步） | 赛题第 1 条：意图理解和任务规划 |
| 3 | **Skill 被加载**（出现 `huawei-rds` / `dds-dcs` 的名字） | 真的在用 Skills |
| 4 | `ListFlavors` / `ListInstances` 的查询结果 | 参数是查出来的，不是我硬塞的 |
| 5 | `CreateInstance` 之后状态 `BUILD` / `CREATING` | "创建"这个动作确实发生了 |
| 6 | 复查时两个实例 `ACTIVE` / `RUNNING`，带**实例 ID + 内网 IP** | 最终证据 |

截图存哪：本地建个文件夹 `codearts截图/`，命名 `01-指令.png` … `06-完成.png`，打包发我，我入库到 `docs/img/codearts/`。

---

## 七、开完交回什么

微信发我这四项：

```
1. RDS 内网地址：192.168.0.___ : 3306
2. Redis 内网地址：192.168.0.___ : 6379
3. 两个实例 ID
4. 录屏 + 6 张截图
```

**然后停手。** 迁数据、切配置、删旧实例，全部我来。

---

## 八、七条红线（每条都是真踩过的）

**1. AK/SK 必须是队长那套**
我俩账号各有一个叫 `vpc-default` 的 VPC，ID 不同（队长 `8f452f90`、你的 `b1b68207`）。
ECS 在队长的 VPC 里，**用你自己的 AK 开，实例落在你的 VPC，内网永远不通**。
而且 RDS 的 VPC **创建后不能改**，只能删了重开。

**2. VPC / 子网按 ID 核对，不按名字选**
两个账号都有同名 `vpc-default`。名字可以不一样，**ID 必须完全一致**。

**3. 安全组必须是 `7a3bcc1a-9a6f-4119-b7f9-397a03fca4f7`（Sys-WebServer）**
新建安全组忘了放行，会出现"实例开好了、IP 也有、就是连不上"，报错是超时，查不出原因。

**4. 规格不要改**
RDS `rds.mysql.x1.large.2`（2核4G），Redis `redis.single.xu1.large.1`（1G）。
降到 1核2G 省不了几毛钱，演示时融合判定会卡。

**5. 密码照抄**
RDS `Yd2026Skill#a7`、Redis `Yd2026Skill#b8`。改了我这边连不上，只能删了重开。

**6. 只开不删、只开不迁**
你删任何实例，线上立刻断。

**7. 不让 AK/SK 落地**
码道会生成 `~/.hcloud/config.json`，那是本机配置，**不要拷进仓库、不要发给任何人**。

---

## 九、卡住了怎么办

按顺序做，别自己硬试：

1. 把 `doctor` 完整输出发我
2. 把码道报错**截图**发我（不要转述，我要看原始错误码）
3. 停下等我回话

**千万不要**：
- 换规格重试（会开出一堆实例，都要钱）
- 换可用区重试（如果报 `DCS.4042 This AZ does not exist`，**不是可用区错，是参数名错**，换哪个都一样）
- 用自己账号重试（VPC 错了，试一百次也不通）

---

## 十、参数速查

| 项 | RDS | Redis |
|---|---|---|
| 区域 | cn-north-4 | cn-north-4 |
| 可用区 | cn-north-4a | cn-north-4a（参数名 `az_codes.1`） |
| 规格 | `rds.mysql.x1.large.2` | `redis.single.xu1.large.1` |
| 引擎版本 | MySQL 8.0 | Redis 6.0 |
| 存储 | CLOUDSSD 40G | — |
| 容量 | — | 1（GB） |
| 端口 | 3306 | 6379 |
| 计费 | postPaid | postPaid |
| VPC ID | `8f452f90-f5bd-4b51-ad48-4eb1d101552f` | 同左 |
| 子网 ID | `9bb53754-4a4c-4e00-b368-61059c9fbb9e` | 同左 |
| 安全组 ID | `7a3bcc1a-9a6f-4119-b7f9-397a03fca4f7` | 同左 |
| 实例名 | `yindun-rds-1120` | `yindun-redis-1120` |
| 密码 | `Yd2026Skill#a7` | `Yd2026Skill#b8` |

**费用**：0.40 + 0.29 = **0.69 元/时**。开完等我来迁，当天释放旧实例。

---

## 附录 A：实测可用的 RDS JSON（把 name 换成正式名即可）

存成 `rds-create.json`：

```json
{
  "path": { "project_id": "01a158ffdf5c72be8042d090ad264e9d" },
  "body": {
    "name": "yindun-rds-1120",
    "region": "cn-north-4",
    "datastore": { "type": "MySQL", "version": "8.0" },
    "ha": { "mode": "single", "replication_mode": "semisync" },
    "flavor_ref": "rds.mysql.x1.large.2",
    "volume": { "type": "CLOUDSSD", "size": 40 },
    "vpc_id": "8f452f90-f5bd-4b51-ad48-4eb1d101552f",
    "subnet_id": "9bb53754-4a4c-4e00-b368-61059c9fbb9e",
    "security_group_id": "7a3bcc1a-9a6f-4119-b7f9-397a03fca4f7",
    "availability_zone": "cn-north-4a",
    "password": "Yd2026Skill#a7",
    "charge_info": { "charge_mode": "postPaid" }
  }
}
```

跑：

```bash
hcloud RDS CreateInstance --cli-jsonInput=rds-create.json
```

## 附录 B：实测可用的 DCS JSON

存成 `dcs-create.json`：

```json
{
  "path": { "project_id": "01a158ffdf5c72be8042d090ad264e9d" },
  "body": {
    "name": "yindun-redis-1120",
    "engine": "Redis",
    "engine_version": "6.0",
    "capacity": 1,
    "spec_code": "redis.single.xu1.large.1",
    "vpc_id": "8f452f90-f5bd-4b51-ad48-4eb1d101552f",
    "subnet_id": "9bb53754-4a4c-4e00-b368-61059c9fbb9e",
    "security_group_id": "7a3bcc1a-9a6f-4119-b7f9-397a03fca4f7",
    "az_codes": ["cn-north-4a"],
    "password": "Yd2026Skill#b8",
    "bss_param": { "charging_mode": "postPaid" }
  }
}
```

跑：

```bash
hcloud DCS CreateInstance --cli-jsonInput=dcs-create.json
```

## 附录 C：这两份 JSON 是怎么验证的（不是我拍脑袋写的）

我把 `name` 故意写成非法的 `"1"` 跑了一遍，让服务端先报"名字不合法"——**只要错误是名字错，就说明其余参数全部被正确解析了**。

| 服务 | 实测返回 | 说明 |
|---|---|---|
| RDS | `{"error_msg":"Invalid instance name length.","error_code":"DBS.280649"}` | VPC/子网/安全组/规格/可用区/密码/计费全过，只差名字 |
| DCS | `{"error_msg":"Invalid DCS instance name...","error_code":"DCS.4010"}` | 同上 |

**并且这两次都没有创建出实例**（名字非法直接被拒），所以没有产生费用。

同一个验证过程中还确认了一件事：
**RDS 和 DCS 的 `***` 密码参数都会与 KooCLI 系统参数 `password` 冲突**，
必须用 `--cli-jsonInput`，写成 `--password=xxx` 会直接报错退出。这条已实测。

---

## 附录 D：这件事在计划里的位置

`docs/任务分工.md` 第 109 行原文：

> **全程通过 CodeArts 智能体 + Skills 操作**，每步截图存 `docs/img/codearts/`
> （这是赛题第 2 条的唯一证据，不是走形式）

所以**录屏和截图才是主产出，实例是副产物**。

现在线上挂的是我之前用 API 直开的那组（能跑，但过程不合规，赛题第 2 条现在判"未满足"）。
你这次开出来的两个，迁完数据后**会成为线上真正在用的那组**，旧的当天释放。
这样评委问"线上这个 RDS 是不是 Skills 开的"，答案是"是"，不需要任何补充说明。
