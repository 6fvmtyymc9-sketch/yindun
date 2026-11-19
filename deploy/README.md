# deploy —— 银盾服务一键部署

> 一条命令让一台**全新的、什么都没装的机器**跑起银盾服务。
> 对应交接书第一阶段的产出：`install.sh` + 本 README。

---

## 一、前置条件

| 项 | 要求 |
|---|---|
| 操作系统 | Ubuntu 20.04+ 或 Debian 11+（脚本会自己检查，不满足直接报错退出） |
| 权限 | root 或 sudo |
| 网络 | 能访问 apt 源和 deb.nodesource.com（华为云内网可配镜像，见下） |
| `.env` | **必须先准备好**（见第三节），脚本只检查不代填 |

内存 ≥ 1G 即可（现网 ECS 1771MB 正常运行）。

## 二、需要开放哪些端口（安全组）

| 端口 | 用途 | 放行范围 |
|---|---|---|
| 22 | SSH 管理 | 自己的 IP |
| 8080 | 银盾 Web 服务 | 0.0.0.0/0（对外，挂 `Sys-WebServer` 那个安全组） |
| 3306 | RDS MySQL | **只放行 `192.168.0.211/32`**，用独立安全组，**不要复用 Sys-WebServer** |
| 6379 | Redis | **不对公网开放**，同 VPC 内网访问 |

RDS / DCS / ECS 必须同在 `vpc-default`（192.168.0.0/16）的 `subnet-default`（192.168.0.0/24）里，否则内网不通。

## 三、.env 怎么填（人工在服务器上填，不进 git）

文件路径：`/opt/yindun/.env`

```ini
RDS_HOST=<RDS 内网地址>          # 例：rm-xxx.mysql.rds.cn-north-4.myhuaweicloud.com
RDS_PORT=3306
RDS_USER=<用户名>
RDS_PASS=<密码>
RDS_DB=yindun
REDIS_URL=redis://:<Redis密码>@<Redis内网地址>:6379/0
TZ=Asia/Shanghai
```

**⚠️ 逐字核对键名，血泪坑：**
- 是 **`RDS_PASS`**，不是 `RDS_PASSWORD`。写错会**静默降级成内存存储**——看着能跑、重启数据全丢。脚本检测到 `RDS_PASSWORD` 会直接报错拦下。
- `REDIS_URL` 密码前必须带 `:`，格式：`redis://:密码@地址:6379/0`
- RDS 开通时**不要勾"强制 SSL"**，连接代码没配 ssl 参数，勾了直接连不上。

## 四、运行脚本

```bash
# 方式A：从 git 仓库拉（仓库私有，机器上需配好有权限的凭证）
sudo bash install.sh --repo https://github.com/6fvmtyymc9-sketch/yindun.git

# 方式B：本地 scp 传包后指定目录（自动识别仓库的 cloud/ 子目录）
scp -r cloud/ root@<机器IP>:/root/yindun-cloud
sudo bash install.sh --src /root/yindun-cloud

# 方式C：代码已经直接传到 /opt/yindun
sudo bash install.sh
```

**幂等**：重复跑不报错——Node ≥18 已装就跳过、`node_modules` 已存在就跳过（强制重装 `FORCE_INSTALL=1`）、用户/目录已存在不重建、service 单元每次重写保持与脚本一致。`.env` 永远不会被覆盖。

## 五、脚本做了什么（9 步）

1. 检查系统（Ubuntu 20.04+ / Debian 11+），不满足报错退出
2. 装 curl/git/rsync 基础工具
3. 装 Node.js 18+（已有 ≥18 则跳过；**`NODE_BIN=$(command -v node)` 动态取路径**，apt 装在 `/usr/bin`、NodeSource/手工装在 `/usr/local/bin`，写死任何一种换装法就起不来）
4. 建 `/opt/yindun` 目录、建 `yindun` 运行用户
5. 拉代码（git clone 或本地拷，两种都支持；自动识别仓库 `cloud/` 子目录；不覆盖 `.env`/`.git`）
6. `npm install --production`
7. 检查 `.env` 存在且必填项非空（**只检查，不代填**；`RDS_PASSWORD` 拼写错误当场报错）
8. 写 `/etc/systemd/system/yindun.service`（线上模板原样，仅 ExecStart 用动态 node 路径；**不加 EnvironmentFile**——`cloud/src/env.js` 自己解析 `.env`，两套加载会打架；**After/Wants 里的 mysql/redis 改成运行时探测**，本机没有就不写，见第七节最后一条）
9. `systemctl daemon-reload && systemctl enable --now yindun`，然后 `curl http://localhost:8080/api/health` 确认 `"ok":true`

## 六、部署后验证（对照交接书验收标准）

```bash
curl http://localhost:8080/api/health
# {"ok":true,"storage":{"rds":true,"redis":true},...}  rds/redis 都要 true

systemctl restart yindun
curl http://localhost:8080/api/health
# 再看一眼告警/上报记录是否还在 => 在，说明真读到 RDS；丢了 = .env 键名写错降级成内存了
```

健康检查失败排查：

```bash
systemctl status yindun
journalctl -u yindun -n 50 --no-pager
```

## 七、常见坑速查（全部真实发生过）

| 坑 | 后果 | 本脚本的处理 |
|---|---|---|
| `.env` 写成 `RDS_PASSWORD` | 静默内存存储，重启数据丢 | 直接报错拦截 |
| ExecStart 写死 node 路径 | 换装法起不来 | 动态 `command -v node` |
| 加了 EnvironmentFile | 两套 .env 加载打架 | 模板里没有，别自己加 |
| VPC/子网选错 | 内网不通 | 开通时核对 VPC ID `8f452f90-f5bd-4b51-ad48-4eb1d101552f` |
| RDS 开强制 SSL | 连不上 | 开通时不勾，脚本无法替你防 |
| 复用 Sys-WebServer 安全组给 RDS | 3306 暴露风险 | RDS 独立安全组，只放 192.168.0.211/32 |
| **迁到 RDS 后 `systemctl stop mysql`，重启 yindun 又把 mysql 拉起来** | 白吃约 400MB 内存，且旧库继续被写 | 脚本现在探测本机有没有 mysql/redis，没有就不写 `Wants`。老服务器迁完后重跑一次 `install.sh` 即可把这条依赖摘掉 |

## 八、迁到 RDS 的正确顺序（这一步写反了会白折腾一下午）

`mysqldump --databases` 导出的文件里带 `DROP TABLE IF EXISTS` 和 `CREATE DATABASE IF NOT EXISTS`。
所以**先建表、再导 dump** 等于把刚建的表再删一遍、按 dump 里的旧结构重建。
今天这份 dump 是从已经迁移过的库导的，结果碰巧一样；换成更老的 dump 就是"服务启动报缺列"。

正确顺序：

```bash
# 1. 建库（只建库，不建表）
mysql -h <RDS> -u <管理员> -p -e "CREATE DATABASE IF NOT EXISTS yindun CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

# 2. 先导入旧库（表结构+数据一起进来）
mysql -h <RDS> -u <管理员> -p yindun < /root/yindun_backup_YYYY-MM-DD.sql

# 3. 补新版本才有的表（IF NOT EXISTS，已有的不动）
mysql -h <RDS> -u <管理员> -p yindun < sql/schema.sql

# 4. 跑迁移（两个都要，都可重复执行）
mysql -h <RDS> -u <管理员> -p yindun < sql/migrate_20261110_callaudio.sql
mysql -h <RDS> -u <管理员> -p yindun < sql/migrate_20261111_event.sql
```

第 4 步两个文件都不能漏：

- `migrate_20261110_callaudio.sql` —— 把 `yd_report.reporter` 的 ENUM 加 `callaudio`。
  漏了不会报错，**录音复盘的上报会被 MySQL 静默截断成空串**，数据直接丢。
- `migrate_20261111_event.sql` —— 给 `yd_alert` 加 `call_id` / `kind`，事件回放要用。

迁移前基线**一律现查**，不要抄任何文档里的数字（353/7 是 11-16 的、428/32 是 11-18 的，
11-19 上午已经是 1581/1225——同学乙跑评估集灌的，`elder_id` 全是 `demo`）。
原则只有一条：**迁移前存档一次，迁移后核对一次，逐项相等即成功。**

```bash
mysql -u root -p"$(sed -n 's/^RDS_PASS=//p' /opt/yindun/.env)" -N \
  -e "SELECT 'alerts',COUNT(*) FROM yindun.yd_alert UNION ALL SELECT 'reports',COUNT(*) FROM yindun.yd_report;"
```

> ⚠️ `/api/health` 的 `stats:{alerts,reports}` 是 `WHERE elder_id='demo'` 的计数
> （`Db.js:428`），不是总条数；对总数只认不带 `WHERE` 的 `SELECT COUNT(*)`。

