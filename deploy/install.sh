#!/usr/bin/env bash
#===============================================================================
# 银盾服务一键部署脚本（裸机 -> 服务可用）
#
# 用法：
#   方式A 从 git 仓库拉代码（仓库私有，需要有权限的账号）：
#     sudo bash install.sh --repo https://github.com/6fvmtyymc9-sketch/yindun.git [--branch main]
#   方式B 从本地目录拷代码（scp 上传后指定目录，兼容仓库 cloud/ 子目录）：
#     sudo bash install.sh --src /root/yindun-cloud
#   代码已经在 /opt/yindun 里时（如 scp 直接传到 /opt/yindun）：
#     sudo bash install.sh
#
# 铁律（队长交接书约定）：
#   1. 脚本内绝不出现任何密码/Key/AK/SK。.env 由人工在服务器上填写，
#      本脚本只检查"有没有填"，绝不代填。
#   2. 幂等：重复跑不报错，已装好的不重装。
#   3. 不假设任何东西已存在：没有 Node/目录/用户都能自己搞定。
#===============================================================================
set -euo pipefail

APP_DIR="/opt/yindun"
APP_USER="yindun"
SERVICE_NAME="yindun"
REPO_URL=""
SRC_DIR=""
BRANCH=""

log()  { echo -e "\033[32m[install]\033[0m $*"; }
warn() { echo -e "\033[33m[warn]  \033[0m $*"; }
die()  { echo -e "\033[31m[error]\033[0m $*" >&2; exit 1; }

#---------------- 参数解析 ----------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)   REPO_URL="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --src)    SRC_DIR="$2"; shift 2 ;;
    *) die "未知参数: $1 （支持 --repo --branch --src）" ;;
  esac
done

#---------------- 步骤0：权限检查 ----------------
if [[ $EUID -ne 0 ]]; then
  if command -v sudo >/dev/null 2>&1; then
    exec sudo -E bash "$0" "$@"
  fi
  die "请用 root 或 sudo 运行本脚本"
fi

#---------------- 步骤1：检查系统 ----------------
[[ -r /etc/os-release ]] || die "读不到 /etc/os-release，不是标准 Linux 发行版"
. /etc/os-release
case "${ID:-}" in
  ubuntu|debian) ;;
  *) die "仅支持 Ubuntu 20.04+ / Debian 11+，当前系统: ${ID:-unknown}" ;;
esac
if [[ -n "${VERSION_ID:-}" ]]; then
  MAJOR="${VERSION_ID%%.*}"
  if { [[ "$ID" == "ubuntu" ]] && [[ "$MAJOR" -lt 20 ]]; } || \
     { [[ "$ID" == "debian" ]] && [[ "$MAJOR" -lt 11 ]]; }; then
    die "$ID $VERSION_ID 版本过低，要求 Ubuntu 20.04+ / Debian 11+"
  fi
else
  warn "未识别到版本号（可能是 sid），继续执行"
fi
log "系统检查通过: ${PRETTY_NAME:-$ID}"

#---------------- 步骤2：安装基础工具 ----------------
if ! command -v curl >/dev/null 2>&1 || ! command -v rsync >/dev/null 2>&1 \
   || ! command -v git >/dev/null 2>&1; then
  log "安装基础工具 curl/git/rsync ..."
  apt-get update -y || warn "apt-get update 失败（网络原因），尝试继续"
  DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates git rsync gnupg
else
  log "基础工具已齐，跳过安装"
fi

#---------------- 步骤3：Node.js 18+（幂等，已有则跳过）----------------
need_node=1
if command -v node >/dev/null 2>&1; then
  NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
  if [[ "$NODE_MAJOR" -ge 18 ]]; then
    need_node=0
    log "Node $(node -v) 已安装，跳过"
  else
    warn "Node $(node -v) 版本低于 18，重新安装"
  fi
fi
if [[ $need_node -eq 1 ]]; then
  log "通过 NodeSource 安装 Node.js 18 ..."
  curl -fsSL https://deb.nodesource.com/setup_18.x | bash -
  DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs
fi
NODE_BIN="$(command -v node)"
[[ -x "$NODE_BIN" ]] || die "安装后仍找不到 node 可执行文件"
log "使用 Node: $NODE_BIN ($(node -v))"

#---------------- 步骤4：建目录、建运行用户 ----------------
id -u "$APP_USER" >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin "$APP_USER"
mkdir -p "$APP_DIR"
log "目录 $APP_DIR 与用户 $APP_USER 就绪"

#---------------- 步骤5：拉代码（git clone 或本地拷，两种都支持）----------------
# 说明：仓库里服务代码在 cloud/ 子目录（cloud/src/server.js），自动识别。
copy_code() {
  local src_root="$1"
  local pick=""
  if   [[ -d "$src_root/cloud/src" ]]; then pick="$src_root/cloud"
  elif [[ -d "$src_root/src"     ]]; then pick="$src_root"
  else die "$src_root 里既没有 cloud/src 也没有 src，代码路径不对？"
  fi
  # --delete 之前必须确认源里有 server.js，否则一个空目录就能把线上删空
  [[ -f "$pick/src/server.js" ]] || die "$pick/src/server.js 不存在，拒绝执行 rsync --delete"
  log "从 $pick 拷贝代码到 $APP_DIR（.env/.git 不会覆盖）"
  # 【2026-11-19 加 --delete】不带 --delete 时，新版本删掉/改名的文件会残留在服务器上：
  #   旧 rules.json 还在 require 路径上 → 线上实际加载的规则与脚本打印的版本号不一致。
  # 但 --delete 会删掉"源目录里没有"的一切，所以下面这些必须排除：
  #   node_modules —— npm install 生成，不排除则每次部署都要重装
  #   data         —— 运行期数据（data/pairs.json 配对关系、data/voice/ 语音），删了老人配对就没了
  #   .drill-last  —— 演练上次触发时间，删了会重复提醒
  #   tools        —— 运维脚本（check_storage.js 之类），不属于发布物
  #   .env / .git  —— 配置与版本库，本来就不该被覆盖
  rsync -a --delete \
    --exclude '.env' --exclude '.git' \
    --exclude 'node_modules' \
    --exclude 'data' --exclude '.drill-last' \
    --exclude 'tools' \
    "$pick"/ "$APP_DIR"/
}

if [[ -n "$SRC_DIR" ]]; then
  [[ -d "$SRC_DIR" ]] || die "--src 目录不存在: $SRC_DIR"
  copy_code "$SRC_DIR"
elif [[ -n "$REPO_URL" ]]; then
  TMP="$(mktemp -d)"
  log "git clone $REPO_URL ..."
  git clone --depth 1 ${BRANCH:+-b "$BRANCH"} "$REPO_URL" "$TMP/repo"
  copy_code "$TMP/repo"
  rm -rf "$TMP"
else
  [[ -f "$APP_DIR/src/server.js" ]] || die "未指定 --repo/--src，且 $APP_DIR/src/server.js 不存在"
  log "使用 $APP_DIR 里已有代码"
fi

#---------------- 步骤6：npm install --production（幂等）----------------
cd "$APP_DIR"
if [[ ! -d node_modules ]] || [[ -n "${FORCE_INSTALL:-}" ]]; then
  log "npm install --production（等价 --omit=dev）..."
  npm install --omit=dev --no-audit --no-fund
else
  log "node_modules 已存在，跳过 npm install（FORCE_INSTALL=1 可强制重装）"
fi
chown -R "$APP_USER:$APP_USER" "$APP_DIR"

#---------------- 步骤7：检查 .env（只检查，绝不代填）----------------
ENV_FILE="$APP_DIR/.env"
REQUIRED_KEYS=(RDS_HOST RDS_PORT RDS_USER RDS_PASS RDS_DB REDIS_URL)
[[ -f "$ENV_FILE" ]] || die "缺少 $ENV_FILE 。请手工创建并填写（该文件不进 git）：\n      RDS_HOST=  RDS_PORT=3306  RDS_USER=  RDS_PASS=  RDS_DB=yindun\n      REDIS_URL=redis://:<密码>@<Redis内网地址>:6379/0  TZ=Asia/Shanghai"

# 常见拼写错误当场拦截：键名必须是 RDS_PASS，写成 RDS_PASSWORD 会静默降级成内存存储
if grep -q '^RDS_PASSWORD=' "$ENV_FILE"; then
  die "$ENV_FILE 里写的是 RDS_PASSWORD，正确键名是 RDS_PASS（少个 WORD）！改完再跑"
fi
missing=()
for k in "${REQUIRED_KEYS[@]}"; do
  val="$(sed -n "s/^${k}=//p" "$ENV_FILE" | head -1 | tr -d ' \t\r\"')"
  [[ -z "$val" ]] && missing+=("$k")
done
if [[ ${#missing[@]} -gt 0 ]]; then
  die "以下必填项缺失或为空: ${missing[*]}\n      请手工编辑 $ENV_FILE 填好后重新运行本脚本（脚本不代填）"
fi
grep -q '^TZ=' "$ENV_FILE" || warn ".env 里没有 TZ，建议加 TZ=Asia/Shanghai"
log ".env 检查通过（必需项齐全）"

# 把实际部署的规则版本打出来。录屏、答辩、以及"以为推了新规则其实推了旧代码"
# 这三种场合都用得上 —— 版本号是唯一能三秒内自证的凭据。
if [[ -f "$APP_DIR/config/rules.json" ]]; then
  RULE_VER="$("$NODE_BIN" -p "require('$APP_DIR/config/rules.json').version" 2>/dev/null || echo '?')"
  RULE_KW="$("$NODE_BIN" -p "(require('$APP_DIR/config/rules.json').sms_keywords||[]).length" 2>/dev/null || echo '?')"
  log "代码里的规则版本: $RULE_VER · 关键词 $RULE_KW 条"
fi
APP_PORT="$(sed -n 's/^PORT=//p' "$ENV_FILE" | head -1 | tr -d ' \t\r\"')"
APP_PORT="${APP_PORT:-8080}"

#---------------- 步骤8：写 systemd 服务单元（模板来自线上机器，勿改结构）----------------
# 差异说明（队长交接书）：
#   - ExecStart 用 NODE_BIN 动态取，兼容 apt 装（/usr/bin）与 NodeSource/手工装（/usr/local/bin）
#   - 不加 EnvironmentFile：cloud/src/env.js 自己解析 /opt/yindun/.env，两套加载会打架
#   - Description 与线上一致，方便 journalctl / grep 对照，别自己改名字
#
# 【2026-11-18 改：本机依赖改成运行时探测】
#   原来固定写 After/Wants=mysql.service redis-server.service —— 那是"数据库和代码装在
#   同一台 ECS"阶段的写法。迁到 RDS / DCS 之后要 systemctl stop mysql redis-server，
#   可只要这两个单元还挂在 Wants 里，重启 yindun 就会把它们一起拉起来：
#   停掉的 mysql 又活了、又吃四百兆内存，而它的数据已经不该再被写。
#   所以现在：本机真有这俩服务才写依赖；没有（新机器直连 RDS）就只依赖 network。
LOCAL_DEPS=""
for u in mysql.service mariadb.service redis-server.service redis.service; do
  if [[ -n "$(systemctl list-unit-files "$u" --no-legend 2>/dev/null || true)" ]]; then
    LOCAL_DEPS="$LOCAL_DEPS $u"
  fi
done
LOCAL_DEPS="$(echo "$LOCAL_DEPS" | sed 's/^ *//; s/ *$//')"
DEP_AFTER="network.target"
DEP_WANTS=""
if [[ -n "$LOCAL_DEPS" ]]; then
  DEP_AFTER="network.target $LOCAL_DEPS"
  DEP_WANTS="Wants=$LOCAL_DEPS"
  log "检测到本机数据库服务: $LOCAL_DEPS（会写进 After/Wants）"
else
  log "本机没有 mysql/redis 服务，单元只依赖 network（直连 RDS/DCS 的正常形态）"
fi

# 【2026-11-19 改：运行用户与加固】
#   原来建了 yindun 用户、也 chown 了目录，但单元里没有 User= —— 服务实际以 root 跑（自相矛盾）。
#   现在补上 User/Group，并按最小权限收紧。★首次部署若起不来，先看第 9 步的排查提示。
#
#   注意：如果这台机器之前以 root 跑过服务，运行期生成的 data/、.drill-last 归 root 所有，
#   yindun 用户读写不了 —— 所以写单元前再 chown 一次（幂等，代价很小）。
chown -R "$APP_USER:$APP_USER" "$APP_DIR"
# .env 里有数据库密码：只给运行用户读，别人一律不可读
chown "$APP_USER:$APP_USER" "$ENV_FILE"
chmod 640 "$ENV_FILE"
log "已把 $APP_DIR 与 .env 交还给 $APP_USER（.env 权限 640）"

cat > /etc/systemd/system/${SERVICE_NAME}.service <<EOF
[Unit]
Description=Yindun Anti-Fraud Cloud Service
After=${DEP_AFTER}
${DEP_WANTS}
# 启动即崩时不要无限重启：60 秒内最多 5 次，超过就进入 failed 等人工处理。
# （不加这两行，.env 写错会变成"每 3 秒重启一次"的无限循环，1 核 2G 的机器会被拖死）
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=${APP_USER}
Group=${APP_USER}
WorkingDirectory=${APP_DIR}
ExecStart=${NODE_BIN} src/server.js
Restart=always
RestartSec=3
LimitNOFILE=65535

# 内存上限：ECS 只有 2G，服务正常 150~300MB、跑模型调用峰值约 500MB。
# 超限时 systemd 只杀本服务（SSH 仍可登录排查），比整机 OOM 卡死好。
MemoryMax=768M

# ---- 最小权限 ----
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=${APP_DIR}
RestrictSUIDSGID=true
CapabilityBoundingSet=

[Install]
WantedBy=multi-user.target
EOF
log "已写入 /etc/systemd/system/${SERVICE_NAME}.service (ExecStart=$NODE_BIN)"

#---------------- 步骤9：启动并健康检查 ----------------
systemctl daemon-reload
systemctl enable --now "$SERVICE_NAME"
log "服务已 enable --now，等待健康检查..."

resp=""
for i in $(seq 1 30); do
  sleep 2
  resp="$(curl -fsS "http://localhost:${APP_PORT}/api/health" 2>/dev/null || true)"
  if echo "$resp" | grep -q '"ok"[[:space:]]*:[[:space:]]*true'; then break; fi
  resp=""
done
[[ -n "$resp" ]] || {
  warn "健康检查 60 秒内未通过，排查命令："
  warn "  systemctl status $SERVICE_NAME"
  warn "  journalctl -u $SERVICE_NAME -n 50 --no-pager"
  warn ""
  warn "常见原因（按概率排）："
  warn "  1) 单元状态显示 start-limit-hit —— 服务连续崩了 5 次被 systemd 拦住。"
  warn "     先看日志再放行：systemctl reset-failed $SERVICE_NAME 之后 systemctl start $SERVICE_NAME"
  warn "  2) 日志里 EACCES / Permission denied —— 权限收得太紧。"
  warn "     先确认：chown -R $APP_USER:$APP_USER $APP_DIR && chmod 640 $APP_DIR/.env"
  warn "     仍然失败再把 ProtectSystem=strict 临时改成 ProtectSystem=full，重载后重试"
  warn "  3) 端口被占 —— ss -lntp | grep ${APP_PORT}"
  exit 1
}
log "健康检查通过: $resp"
echo "$resp" | grep -q '"rds":true'   || warn "rds 尚为 false —— 检查 .env 键名是否为 RDS_PASS、RDS 是否同 VPC"
echo "$resp" | grep -q '"redis":true' || warn "redis 尚为 false —— 检查 REDIS_URL 格式: redis://:<密码>@<地址>:6379/0"

cat <<EOF

============================================================
  银盾服务部署完成
    目录     : $APP_DIR
    服务     : $SERVICE_NAME (systemd, 开机自启)
    健康接口 : http://localhost:${APP_PORT}/api/health
  下一步验收（对照交接书）：
    1. 重启服务后告警/上报记录仍在  => 说明真读到 RDS，不是内存
    2. /api/health 里 rds/redis 均为 true
    3. 每一步截图存到 docs/img/codearts/
============================================================
EOF
