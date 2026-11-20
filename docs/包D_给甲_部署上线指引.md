# 赛题第 5 环节「部署上线」· 执行指引（给甲）

> 2026-11-20 · 对应赛题原文：「用智能体 + Skills 把作品部署到华为云」
> 本文只写**能落地**的路径，每条都标了实测依据和前置条件。

---

## 一、为什么这一环节还没过

赛题五环节里，前四项都有码道参与的证据，只有「部署上线」是空的：

- 现状：`scp` 传包 + 手工写 systemd unit 文件
- 证据：`docs/交付文档.md` 6.4 自己写着「部署上线（scp + systemd）是手工完成的」

**评委要看的是"智能体参与了部署"，不是"服务跑起来了"。** 服务早就跑着，这一项仍然算缺。

---

## 二、目标环境（已实测，不是推测）

| 项 | 实测值 | 取法 |
|---|---|---|
| 主机 | ECS `ecs-b993` · 公网 `123.249.25.139` | `ssh root@123.249.25.139 hostname` |
| 系统 | Ubuntu 22.04.5 LTS | `/etc/os-release` |
| Node | v18.20.8 / npm 10.8.2 | `node --version` |
| 已有工具 | git 2.34.1、rsync 3.2.7、curl 7.81 | `command -v` |
| 服务 | systemd unit `yindun`，状态 **active** | `systemctl is-active yindun` |
| 安装目录 | `/opt/yindun` | `ls /opt/yindun` |
| 服务端口 | 8080 | `/api/health` 可访问 |

### 一个关键约束：ECS 到 GitHub 不通

```
$ git ls-remote https://github.com     → 无输出（不通）
$ curl -I https://obs.cn-north-4.myhuaweicloud.com → HTTP/1.1 405（通，405 是 OBS 对 HEAD 的正常响应）
```

**所以「ECS 上 git clone 代码」这条路不存在。** 制品必须经 OBS 中转。

---

## 三、三个方案，按可行性排

| 方案 | 怎么做 | 证据强度 | 前置成本 | 判定 |
|---|---|---|---|---|
| **A. CodeArts Deploy** | `huawei-cloud-deployment-task-management` 建应用/任务/启动 | ★★★ 最正统 | **高**：要 CodeArts 项目 + ECS 装 CloudDeploy agent | ❌ 本次不用 |
| **B. OBS 制品 + SSH 执行** | OBS 传包 → SSH 拉包 → 部署脚本 | ★★☆ | 中：一个受限部署用户 + 一把临时密钥 | ✅ **推荐** |
| **C. OBS 制品 + 定时拉取** | 甲只传 OBS，ECS 侧定时器自动部署 | ★★☆ | 低，但部署是异步的，录屏看不到"执行"瞬间 | 备选 |

### 为什么不用 A

CodeArts Deploy 有两个硬门槛，我们一项都不满足：

1. **需要 CodeArts (DevCloud) 项目** —— 只有 IAM 项目会直接报 `Deploy.00016902 项目不存在`
2. **目标主机必须装 CloudDeploy agent 并在线** —— 没装 agent 时任务必然「host offline」

装 agent + 建项目的准备时间约 2 小时，**而且要在生产 ECS 上装一个常驻 agent**，风险与收益不成比例。

### 为什么选 B

`huawei-ecs` 这个 Skill 明确支持远程执行：

```
ssh -o StrictHostKeyChecking=accept-new -i <key> root@<eip> 'command'
```

它的触发词里就写着「HC活动部署 / ECS+Nginx部署后端服务 / Nodejs部署 / 上线后端服务」——
说明这是设计内的路径，不是我们硬凑的。

---

## 四、方案 B 的完整流程

### 4.0 前置（队长准备，甲不用做）

- [ ] ECS 上建受限用户 `deploy`，只允许执行 `/opt/yindun/deploy.sh`（sudoers 白名单）
- [ ] 生成一把**专用部署密钥**，只加到 `deploy` 用户的 `authorized_keys`
- [ ] ECS 上写 `/opt/yindun/deploy.sh`：从 OBS 拉包 → 校验 → 解压 → 重启服务
- [ ] OBS 建桶 `yindun-deploy`（与 ECS 同 region `cn-north-4`）
- [ ] 安全组临时放行 22 端口到码道沙箱出口 IP（用完收回）
- [ ] **部署完成后：删 `deploy` 用户、删密钥、收回安全组规则**

> 为什么建受限用户而不是直接给 root：即使密钥泄露，攻击面也只剩"能跑一个部署脚本"。
> 给 root 等于把整台机器交出去。

### 4.1 甲：上传制品（Skills 证据 ①）

```
用 huawei-obs skill，把打包好的代码传到桶 yindun-deploy，对象名 yindun-<版本>.tar.gz
```

**录屏要点**：Skill 被加载的瞬间、上传成功的返回（对象名 + 大小）。

### 4.2 甲：执行部署（Skills 证据 ②）

```
用 huawei-ecs skill，SSH 到 123.249.25.139，以 deploy 用户执行：
  /opt/yindun/deploy.sh yindun-<版本>.tar.gz
```

**录屏要点**：SSH 命令原文、脚本输出的每一步（下载 → 校验 → 解压 → 重启 → 健康检查）。

### 4.3 甲：验证（证据 ③）

```bash
curl -s http://123.249.25.139:8080/api/health
```

必须看到：

```json
"ok": true, "rds": true, "redis": true
```

**这一步不能省。** 部署完不验证，等于只说"我传了包"，没说"服务真的起起来了"。

---

## 五、录屏必须拍到的画面（缺一条说服力减半）

| # | 画面 | 为什么必须 |
|---|---|---|
| 1 | 调用 Skill 的瞬间（如 `retrieve_skill name=huawei-obs`） | 没有这一帧，"用 Skills"无从证明 |
| 2 | 上传成功的返回（对象名 + 大小） | 证明制品真的进了云 |
| 3 | SSH 命令原文 + 部署脚本输出 | 证明部署动作发生在云上，不是本机模拟 |
| 4 | `curl /api/health` 返回 `rds:true / redis:true` | 证明服务真的起来了 |
| 5 | 浏览器打开演示页正常 | 证明对外可访问 |

**别做的事**：不要在 prompt 里写"用 `hcloud OBS PutObject --bucket=... --key=..."这种 API 细节。
一旦写死参数，评委就无法判断是 Skill 生成的还是你手写的——**Skill 的价值恰恰在于"它自己知道怎么调"**。

---

## 六、命令里不能出现的东西（红线）

| 禁止 | 原因 |
|---|---|
| 在 prompt 里写具体 API 参数、参数前缀 | Skill 的存在意义被抹掉 |
| 用 `sandbox_exec_one_shot` 跑部署 | 那条链路连的是远端 workspace，不是我们的 ECS，凭据不在那里 |
| 给 root 权限的私钥 | 一把钥匙开整台机器 |
| 把密钥/密码写进部署脚本或任务参数 | 会进日志、进录屏 |
| 部署完不删临时用户和密钥 | 留了一个永久后门 |

---

## 七、与「第 2 环节」的衔接

甲现在做的是第 2 环节（用 Skills 开 RDS + DCS）。**两个环节最好在同一个会话里连着做**：

1. 开 RDS → 开 Redis → 拿实例 ID 和内网 IP
2. **不要换会话**，直接接第 5 环节：上传制品 → 部署 → 验证

原因是：换会话要重新配凭据、重新过闸门，等于把今天的坑再踩一遍。

如果中间必须停（比如等队长切 `.env`），就把会话留着别关，做完再回来。

---

## 八、验收标准

甲交回以下东西才算完成：

- [ ] 连续录屏一段（不剪接），覆盖第五节五个画面
- [ ] OBS 上的对象名与大小
- [ ] `deploy.sh` 的完整输出文本
- [ ] `/api/health` 的完整 JSON
- [ ] 浏览器打开演示页的截图

**任何一条缺，这一环节就还是「未满足」。** 因为赛题问的是"使用价值"，过程断了就没法论证。
