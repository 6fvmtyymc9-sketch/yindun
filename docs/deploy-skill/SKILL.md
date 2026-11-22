---
name: yindun-deploy
description: 把银盾（YinDun）反诈服务的改动安全部署到华为云 ECS，并完成独立复验。当用户说"部署""上线""发到线上""更新服务器上的版本"时使用。本 Skill 只做增量文件替换与重启，禁止整目录同步，禁止触碰 .env。
version: 1.0.0
---

# 银盾 · 部署到华为云 ECS

## 环境常数

| 项 | 值 |
|---|---|
| 主机 | `root@123.249.25.139`（免密 SSH 已配好） |
| 部署目录 | `/opt/yindun/` |
| 代码目录 | `/opt/yindun/src/`（服务端 js） |
| 静态目录 | `/opt/yindun/public/`（演示页 index.html） |
| systemd 服务 | `yindun` |
| 配置文件 | `/opt/yindun/.env`（**只读，禁止写入**，权限 600） |
| 公网入口 | `http://123.249.25.139:8080/` |

---

## 三条铁律（违反任何一条都会把线上搞挂）

1. **禁止 `rsync -a --delete`、禁止整目录同步、禁止 `scp -r` 传整个目录。**
   `.env` 里是数据库密码与模型 Key，不在 git 里。整目录同步会把 `.env` 一起抹掉，
   服务随后起不来，而报错只说"连不上数据库"，很难反查。
   **只传本次改动的那几个文件，逐个点名。**

2. **禁止把开发仓的文件整份拷进交付仓再部署。**
   2026-11-17 发生过一次：用开发仓的 `server.js` 覆盖交付仓版本（793 行 → 丢了 910 行版），
   一夜丢了 `/api/callaudio` 等 6 个接口。要改就在交付仓里改，或先 `git diff` 看差异。

3. **重启后必须做独立复验，不许只看"命令没报错"。**
   `systemctl restart` 成功 ≠ 新版本生效（可能启动就崩了，systemd 又静默拉起旧进程）。

---

## 执行步骤

### 第 1 步：取部署前基线

```bash
curl -s http://123.249.25.139:8080/api/health > /tmp/yindun-before.json
python3 -c "import json;d=json.load(open('/tmp/yindun-before.json'));print(d['build']['codeId'], d['build']['startedAt'])"
```

记下 `codeId`（代码内容 md5 前 12 位）与 `startedAt`（进程启动时刻）。
**这两个数是后面判断"到底换没换"的唯一依据。**

### 第 2 步：确认待传文件与线上现状

```bash
# 线上 src 有几个 js、public 有几个文件
ssh root@123.249.25.139 "ls -1 /opt/yindun/src/*.js | wc -l; ls -1 /opt/yindun/public/"
```

线上 `public/` 正常情况下**只有** `index.html`（加少量验收截图）。若出现其他 html，
先问清楚再动手，不要顺手删。

### 第 3 步：逐文件传输（只传改动的那几个）

```bash
scp cloud/src/Guard.js root@123.249.25.139:/opt/yindun/src/Guard.js
scp cloud/public/index.html root@123.249.25.139:/opt/yindun/public/index.html
```

**不要用通配符 `scp cloud/src/*.js`**——那会把你本地所有改动一并带上去，
包括还没验证过的。

### 第 4 步：重启并等待就绪

```bash
ssh root@123.249.25.139 "systemctl restart yindun && sleep 3 && systemctl is-active yindun"
```

必须看到 `active`。若为 `failed`，立即执行下方「回滚」。

### 第 5 步：四项独立复验（缺一不可）

```bash
# ① 服务活着，且 codeId 变了（证明真的换了代码，不是重启了旧进程）
curl -s http://123.249.25.139:8080/api/health | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('ok:', d['ok'])
print('codeId:', d['build']['codeId'], 'files:', d['build']['files'])
print('rules:', d['rules']['version'], d['rules']['keywords'])
print('storage: rds=%s redis=%s' % (d['storage']['rds'], d['storage']['redis']))
"

# ② 首页可访问，且字节数与本地一致
curl -s -o /dev/null -w '%{http_code} %{size_download}\n' http://123.249.25.139:8080/
wc -c cloud/public/index.html

# ③ 逐字节比对：把线上文件取回来和本地比
scp root@123.249.25.139:/opt/yindun/public/index.html /tmp/yindun-online.html
diff --strip-trailing-cr /tmp/yindun-online.html cloud/public/index.html && echo "逐字节一致"

# ④ .env 未被触碰（大小 1043 字节、权限 600）
ssh root@123.249.25.139 "stat -c '%s bytes %a' /opt/yindun/.env"
```

**四项全过才算部署成功。** 每项都要看具体数值，不能因为"没报错"就当过了。

### 第 6 步：留证

把第 1 步与第 5 步的输出一起存到 `docs/deploy-skill/`：

- `before-YYYYMMDD.json`（部署前 health）
- `after-YYYYMMDD.json`（部署后 health）
- 在 `docs/部署验收_YYYYMMDD.md` 追加一行时间线

---

## 回滚

第 4 步 `is-active` 返回 `failed`，或第 5 步 `ok:false`：

```bash
ssh root@123.249.25.139 "journalctl -u yindun -n 50 --no-pager"
```

先看日志再动手。若是语法错误导致的崩溃，用 git 恢复上一版重新传：

```bash
git checkout HEAD~1 -- cloud/src/<出错的文件>.js
scp cloud/src/<出错的文件>.js root@123.249.25.139:/opt/yindun/src/
ssh root@123.249.25.139 "systemctl restart yindun"
```

**禁止在服务器上直接改代码**——下次部署会被覆盖，改动凭空消失。
**禁止 `git reset --hard` 回滚整个仓库**——会连带丢别的改动。

---

## 这个 Skill 做不了什么（必须如实告知用户）

| 做不到 | 原因 / 该找谁 |
|---|---|
| 编译安卓 APK | 需要本地 Android SDK 与 JDK 17，走 `./gradlew assembleDebug` 或 `app/build2.sh` |
| 数据库建表与迁移 | 需要 SQL 脚本与 RDS 账号，属独立流程，不在本 Skill 范围 |
| 修改 `.env`（换数据库地址、加 Key） | 属高危操作，必须人工确认后单独执行，且改完要 `systemctl restart` |
| 开通 RDS / DCS 等云资源 | 走「用 Skills 开通云服务」那条链路，与本 Skill 独立 |
| 验证老人端真机行为 | 需要真机或模拟器，本 Skill 只保证"服务端这一侧是对的" |

---

## 已验证记录

| 日期 | 改动 | 复验结果 |
|---|---|---|
| 2026-11-22 | `Guard.js`（微信通道判定）+ `index.html`（微信演示层） | codeId `0eb16dc8dd88`，19 个 js，rds/redis 均 true，首页 159,145 字节，逐字节一致，`.env` 1043B/600 未变 |
