# 包 C · 给甲：用码道 Skills 开通 RDS 与 DCS

> 2026-11-19 20:15 · v3（**按老师《银盾改造方案》原文逐条重写**）
> 执行人：同学甲（码道侧）
> 依据三份，缺一不可：
> - 赛题任务要求第 2 条「基于已有智能体和 Skills，进行关联 RDS、Redis 等华为云服务的快速开通创建」
> - 老师《银盾改造方案》阶段 C 的 **C1 / C2 / C3**
> - `docs/任务分工.md` 第 103~140 行「包 C」
>
> **本文每条命令都在本机实测过**，末尾附录给出实测返回，不是推测。

---

## 零、先划清分工（这块不看会白干）

老师原文把「阶段 C」的 C2~C5 都记在你名下。但有一条硬限制：

> **RDS 只开内网，你的电脑连不上它。** 所以"建 `yindun` 库账号""导数据""改 `.env`"这三件事
> 只能由队长在 ECS 上做。你负责的是**开通**，不是运维。

| 老师原文 | 谁做 | 说明 |
|---|---|---|
| C1 建 `码道操作记录_开通与部署.md` + 截图目录 | 队长已建好骨架 | 你只要出截图，队长填表 |
| **C2 开通 RDS for MySQL** | **你** | 本文第三节 |
| **C3 开通 DCS Redis** | **你** | 本文第四节 |
| C2 附带的"安全组只允许 ECS 内网 IP" | **你**（建组） | 本文第二节，必须在你开实例之前 |
| C2 附带的"建 yindun 账号只授权 yindun 库" | 队长 | 内网限制，你连不上 |
| C2 附带的"估算月费并记录" | 队长已算好 | 见附录 D，你不用管 |
| C4 建表并迁移数据 | 队长 | 需要 ECS 权限 |
| C5 改 `.env` 并切换 | 队长 | 需要 ECS 权限 |
| C6 OCR | 已有 | 代码在跑，只差一次真实调用 |
| C7 用 CodeArts 部署 | 版本冻结后再说 | 不是这次的事 |

**你只做一件事：在码道里用 Skills 把两个实例开出来，全程录屏 + 截图。**

不要迁数据、不要改线上配置、不要删任何东西。开完就停。

| 要开的东西 | 名字 | 规格 |
|---|---|---|
| RDS for MySQL | `yindun-rds-1120` | MySQL 8.0 单机 2 核 4G / 40G SSD |
| DCS Redis | `yindun-redis-1120` | Redis 6.0 单机 1G |

> **为什么规格写死成 2 核 4G**：老师 C2 要求"最小规格"。我调用
> `ListFlavors` 实测过，MySQL 8.0 **单机最小档就是 2 核 4G**（`rds.mysql.x1.large.2`），
> 再往下没有更小的。所以这一档就是"最小"，不是我们挑贵的。

---

## 一、开跑之前先确认三件事

| # | 确认什么 | 不满足怎么办 |
|---|---|---|
| 1 | 你电脑上装了**码道**（CodeArts Agent 或 VS Code 码道插件） | 先装，装完再往下看 |
| 2 | 你的码道能打开终端（能敲 `node -v` 出结果） | 找不到终端就问队长 |
| 3 | 你能收到队长单独发的 **AK/SK**（**队长的账号**，不是你自己的） | 没收到先要，别自己注册 |

---

## 二、第 1 步：环境自检（3 分钟）

在**码道的终端**里敲：

```bash
node -v
```

**必须 ≥ v22.19。** 低于这个数就停，发截图给队长。

```bash
npx --yes huaweicloud-devkit@latest doctor
```

第一次跑会有一堆 `[FAIL]`，**这是正常的**（还没装）。把输出留着，装完要对比。

> ⚠️ 报 `npx: command not found` = Node 没装好，停下找队长。

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
| `Skills installed (29)` | 29 个华为云技能装好，含 `huawei-rds` / `huawei-cloud-dds-dcs-instance-management` / `huawei-vpc` |
| 唯一一条 `[WARN] 检测到码道沙箱模式` | 正常，下一步解决 |
| 任何 `[FAIL]` | **停下，整段输出发队长** |

**这一步截一张图**（`01-doctor.png`）。

---

## 四、第 3 步：关沙箱 + 配凭据（2 分钟）

### 4.1 关沙箱（必须，不关后面全废）

码道 → **设置 → 对话流 → 智能体 → 终端命令运行模式 → 选「自动运行」**

原因：沙箱模式下 KooCLI 写不进 `~/.hcloud/`，会卡在隐私协议那一步。

### 4.2 配 AK/SK

AK/SK 队长从微信单独发你，**不要写进任何文件、不要提交进仓库、不要贴群**。

```bash
hcloud configure set --cli-access-key=你的AK --cli-secret-key=你的SK --cli-region=cn-north-4 --cli-profile=default
```

**必须验证真的通了**：

```bash
hcloud RDS ListInstances --cli-region=cn-north-4
```

**期望**：返回一段 JSON，里面有 `"name": "yindun-demo-1119"`（队长之前开的，能看到就说明凭据对了）。

| 报什么 | 说明 |
|---|---|
| `配置文件中不存在配置项` | 上一条 `configure set` 没成功，重跑 |
| `认证失败` | AK/SK 抄错了，找队长要新的 |

### 4.3 重启会话（关键）

关掉当前码道对话窗口，**重新开一个**。

MCP 工具**只在新会话生效**。不重启，智能体看不到 `huaweicloud_*` 系列工具。

**怎么确认生效**：新会话里问码道「你现在有哪些华为云相关的工具」，能看到 `huaweicloud_check_cli` 这类就对了。

---

## 五、第 4 步：建专用安全组（老师 C2「只允许 ECS 的内网 IP 访问」）

> **这一步不能跳。** 现成的 `Sys-WebServer` 安全组是"同组互通"，
> 里面除了 ECS 还有别的成员；老师要的是**按源 IP 限死**。

**先开录屏，再输入。** 下面是给码道的原话，整段复制：

```
用 huawei-vpc 这个 Skill，在 cn-north-4 建一个安全组，只允许我们 ECS 的内网 IP 访问数据库：

安全组名 yindun-db
然后加两条入方向规则（egress 用默认的全放通，不要动）：
  规则1：ingress / tcp / 端口 3306 / 源 192.168.0.211/32
  规则2：ingress / tcp / 端口 6379 / 源 192.168.0.211/32

注意：
1. CreateSecurityGroup 不需要 vpc_id，不要传，传了会报「不正确的参数:vpc_id」；
2. CreateSecurityGroupRule 的参数前缀是 security_group_rule.，端口用 multiport；
3. 两条规则加完用 ListSecurityGroupRules 复查，把结果打全；
4. 最后把安全组 ID 告诉我，我要拿它去开 RDS 和 Redis。
```

**正常会走**：`CreateSecurityGroup` → 返回安全组 ID → `CreateSecurityGroupRule` × 2 → `ListSecurityGroupRules` 复查。

**这一步截一张图**（`04-sg.png`），要能看到 **安全组 ID + 两条规则的源 IP 是 `192.168.0.211/32`**。

⚠️ **把安全组 ID 抄下来**，下一步要填。

---

## 六、第 5 步：开通 RDS（老师 C2）

**继续录屏。** 整段复制给码道：

```
用 huawei-rds 这个 Skill，在 cn-north-4 开通一个 RDS for MySQL 实例。参数我已经查好了，
请严格按这些执行，不要自己换规格、换可用区、换安全组：

实例名 yindun-rds-1120
引擎 MySQL 8.0
单机（ha.mode = single）
规格 rds.mysql.x1.large.2（2核4G，这是单机最小档）
存储 CLOUDSSD 40G
可用区 cn-north-4a
端口 3306
密码 Yd2026Skill#a7
计费 postPaid，不绑公网 IP
VPC ID    8f452f90-f5bd-4b51-ad48-4eb1d101552f
子网 ID   9bb53754-4a4c-4e00-b368-61059c9fbb9e
安全组 ID <上一步拿到的 yindun-db 的安全组 ID>

执行要求：
1. 先 ListInstances 看 yindun-rds-1120 这个名字有没有被占用；
2. 再 ListFlavors 确认 rds.mysql.x1.large.2 在 cn-north-4a 可用，把结果打出来；
3. 创建时密码参数会和 KooCLI 系统参数 password 冲突，请用 --cli-jsonInput 传 JSON 文件，
   不要用 --password=；
4. body 里的 region 也要填 cn-north-4，和 cli-region 一致，否则报 DBS.280243；
5. 创建后每隔一段查一次状态，直到 ACTIVE，把实例 ID 和内网地址（private_ip）列给我；
6. 报错就把错误码和你的判断一起打出来，然后修正重试。
```

**正常会走**：`ListInstances` 查重 → `ListFlavors` 核规格 → 写 JSON → `CreateInstance` → 轮询到 `ACTIVE`。

**这一屏至少截两张**：`05-rds-create.png`（`BUILD` 状态）、`07-done.png`（`ACTIVE` + 实例 ID + 内网 IP）。

### 6.1 如果卡在密码上（大概率会遇到）

`--password` 与 KooCLI 系统参数同名，直接写在命令里会报：

```
目标API中存在与KooCLI系统参数password同名的参数,无法识别--password的位置
```

**这是实测过的已知问题，不是你操作错了。** 把附录 A 的 JSON 发给它（或自己存成文件跑），就能过。
**附录 A 那份 JSON 我用非法实例名验证过，结构 100% 正确，只差名字。**

### 6.2 如果报"安全组不存在"

说明第 4 步的安全组 ID 抄错了，或安全组建在了别的 region。回去核对 `ListSecurityGroups` 的输出。

---

## 七、第 6 步：开通 DCS Redis（老师 C3）

**继续录屏。** 整段复制：

```
用 huawei-cloud-dds-dcs-instance-management 这个 Skill，在 cn-north-4 开通一个 DCS Redis 实例：

实例名 yindun-redis-1120
引擎 Redis，版本 6.0，单机
规格 redis.single.xu1.large.1（1G）
容量 1
可用区参数名是 az_codes，值是 ["cn-north-4a"]（不是 available_zones，写错会报 DCS.4042）
端口 6379
密码 Yd2026Skill#b8
计费 postPaid
VPC ID    8f452f90-f5bd-4b51-ad48-4eb1d101552f
子网 ID   9bb53754-4a4c-4e00-b368-61059c9fbb9e
安全组 ID 和第 5 步的 RDS 用同一个（yindun-db）

执行要求：
1. 先 ListInstances 查重名；
2. 密码参数同样会和 KooCLI 系统参数冲突，用 --cli-jsonInput；
3. 创建后轮询到 RUNNING，把实例 ID 和内网地址列给我；
4. 报错把错误码打出来再重试。
```

**截图**：`06-redis-create.png`（`CREATING`）、`07-done.png`（`RUNNING` + 实例 ID + 内网 IP）。

### 7.1 报 `DCS.4042 This AZ does not exist` 怎么办

**不是可用区错了，是参数名错了。** 换成 `available_zones` 会一直报这个，换 4a/4b/4c/4g 都一样。
必须用 `az_codes`。

---

## 八、开完交回什么

微信发这四项：

```
1. RDS 内网地址：192.168.0.___ : 3306
2. Redis 内网地址：192.168.0.___ : 6379
3. 两个实例 ID + 安全组 ID
4. 录屏 + 6 张截图
```

**然后停手。** 建 `yindun` 库账号、迁数据、切配置、删旧实例，全部队长来。

---

## 九、必须拍到的 6 个画面（少一个说服力降一半）

| # | 拍什么 | 证明什么 | 文件名 |
|---|---|---|---|
| 1 | 你贴的自然语言指令原文 | 起点是「人话」 | `01-doctor.png` 之前那段录屏里有 |
| 2 | 码道的**任务规划**（它把活拆成几步） | 赛题第 1 条：意图理解和任务规划 | 录屏 |
| 3 | **Skill 被加载**（出现 `huawei-vpc` / `huawei-rds` / `dds-dcs` 的名字） | 真的在用 Skills | 录屏 |
| 4 | `ListFlavors` / `ListInstances` 的查询结果 | 参数是查出来的，不是队长硬塞的 | 录屏 |
| 5 | `CreateInstance` 之后 `BUILD` / `CREATING` | 「创建」这个动作确实发生了 | `05`、`06` |
| 6 | 复查时 `ACTIVE` / `RUNNING`，带**实例 ID + 内网 IP** | 最终结果 | `07-done.png` |

截图存本地文件夹 `codearts截图/`，命名 `01-doctor.png` … `07-done.png`，打包发队长，
队长入库到 `docs/img/codearts/` 并填 `docs/码道操作记录_开通与部署.md`。

**中间报错别停录屏、别剪。** 智能体自己发现参数错、改参数重试的过程，
恰恰是赛题第 1 条要的「意图理解和任务规划」，比一次成功更有说服力。

---

## 十、七条红线（每条都是真踩过的）

**1. AK/SK 必须是队长那套**
你俩账号各有一个叫 `vpc-default` 的 VPC，ID 不同（队长 `8f452f90`、你的 `b1b68207`）。
ECS 在队长的 VPC 里，**用你自己的 AK 开，实例落在你的 VPC，内网永远不通**。
而且 RDS 的 VPC **创建后不能改**，只能删了重开。

**2. VPC / 子网按 ID 核对，不按名字选**
两个账号都有同名 `vpc-default`。名字可以一样，**ID 必须完全一致**。

**3. 安全组必须用第 4 步新建的 `yindun-db`**
不要图省事填 `Sys-WebServer`。老师 C2 明确要求"只允许 ECS 的内网 IP 访问 3306"，
旧组是整组互通，不满足，而且控制台截图一对比就看得出来。

**4. 规格不要改**
RDS `rds.mysql.x1.large.2`（2核4G，单机最小档），Redis `redis.single.xu1.large.1`（1G）。
降档省不了几毛钱，演示时融合判定会卡；**升档更不行**，老师要的是最小规格。

**5. 密码照抄**
RDS `Yd2026Skill#a7`、Redis `Yd2026Skill#b8`。改了我这边连不上，只能删了重开。

**6. 只开不删、只开不迁**
你删任何实例，线上立刻断。

**7. 不让 AK/SK 落地**
码道会生成 `~/.hcloud/config.json`，那是本机配置，**不要拷进仓库、不要发给任何人**。

---

## 十一、卡住了怎么办

按顺序做，别自己硬试：

1. 把 `doctor` 完整输出发队长
2. 把码道报错**截图**发队长（不要转述，要看原始错误码）
3. 停下等回话

**千万不要**：
- 换规格重试（会开出一堆实例，都要钱）
- 换可用区重试（报 `DCS.4042` 时换哪个都一样，那是参数名错）
- 用自己账号重试（VPC 错了，试一百次也不通）
- 把安全组换成 `Sys-WebServer` 图省事（不满足老师要求）

---

## 十二、参数速查

| 项 | RDS | Redis |
|---|---|---|
| 区域 | cn-north-4 | cn-north-4 |
| 可用区 | cn-north-4a | cn-north-4a（**参数名 `az_codes`**） |
| 规格 | `rds.mysql.x1.large.2` | `redis.single.xu1.large.1` |
| 引擎版本 | MySQL 8.0 | Redis 6.0 |
| 存储 | CLOUDSSD 40G | — |
| 容量 | — | 1（GB） |
| 端口 | 3306 | 6379 |
| 计费 | postPaid | postPaid |
| VPC ID | `8f452f90-f5bd-4b51-ad48-4eb1d101552f` | 同左 |
| 子网 ID | `9bb53754-4a4c-4e00-b368-61059c9fbb9e` | 同左 |
| 安全组 | `yindun-db`（第 4 步新建） | 同左 |
| 放行源 | `192.168.0.211/32` | `192.168.0.211/32` |
| 实例名 | `yindun-rds-1120` | `yindun-redis-1120` |
| 密码 | `Yd2026Skill#a7` | `Yd2026Skill#b8` |

---

## 附录 A：实测可用的 RDS JSON（把 name 和 security_group_id 换成正式的）

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
    "security_group_id": "填第4步新建的 yindun-db 的ID",
    "availability_zone": "cn-north-4a",
    "password": "Yd2026Skill#a7",
    "charge_info": { "charge_mode": "postPaid" }
  }
}
```

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
    "security_group_id": "填第4步新建的 yindun-db 的ID",
    "az_codes": ["cn-north-4a"],
    "password": "Yd2026Skill#b8",
    "bss_param": { "charging_mode": "postPaid" }
  }
}
```

```bash
hcloud DCS CreateInstance --cli-jsonInput=dcs-create.json
```

## 附录 C：这两份 JSON 是怎么验证的（不是拍脑袋写的）

把 `name` 故意写成非法的 `"1"` 跑一遍，让服务端先报"名字不合法"——
**只要错误落在名字上，就说明其余参数（VPC / 子网 / 安全组 / 规格 / 可用区 / 密码 / 计费）全部被正确解析了**。

| 服务 | 实测返回 |
|---|---|
| RDS | `{"error_msg":"Invalid instance name length.","error_code":"DBS.280649"}` |
| DCS | `{"error_msg":"Invalid DCS instance name...","error_code":"DCS.4010"}` |

**这两次都没有创建出实例**（名字非法直接被拒），所以没有产生费用。

## 附录 D：费用（老师 C2 要求"开通前先估算月费并记录"）

| 项 | 单价 | 换算 |
|---|---|---|
| RDS MySQL 2核4G 单机 + 40G SSD | **0.40 元/时** | ≈ 9.6 元/天 ≈ 288 元/月 |
| DCS Redis 1G 单机 | **0.29 元/时** | ≈ 7.0 元/天 ≈ 208 元/月 |
| 合计 | **0.69 元/时** | ≈ 16.6 元/天 ≈ **496 元/月** |

按需计费，**演示结束立即释放**，实际支出以小时计（跑一晚约 5 元）。走代金券。

## 附录 E：这件事在计划里的位置

`docs/任务分工.md` 第 109 行原文：

> **全程通过 CodeArts 智能体 + Skills 操作**，每步截图存 `docs/img/codearts/`
> （这是赛题第 2 条的唯一证据，不是走形式）

所以**录屏和截图才是主产出，实例是副产物**。

现在线上挂的是队长 11-19 19:30 用 API 直开的那组（能跑，但过程不合规，赛题第 2 条现在判"未满足"）。
你这次开出来的两个，迁完数据后**会成为线上真正在用的那组**，旧的当天释放。
这样评委问"线上这个 RDS 是不是 Skills 开的"，答案是"是"，不需要任何补充说明。
