#!/usr/bin/env bash
# setup_db_user.sh — 建受限数据库账号 yindun，只授权 yindun 库
#
# 依据：老师《银盾改造方案》阶段 C · C2-5「建数据库账号 yindun，只授权给 yindun 库」
#       原文第 301 行，且第 313/314/316 行后续命令全部使用 -uyindun
#
# 用法（在 ECS 上执行）：
#   bash setup_db_user.sh <RDS内网地址> <root密码> [新账号密码]
#
# 示例：
#   bash setup_db_user.sh 192.168.0.90 'Yd2026Demo#x9q'
#   bash setup_db_user.sh 192.168.0.90 'Yd2026Demo#x9q' 'Yd2026App#k3'
#
# 特性：
#   - 幂等：重复执行不会报错，只会重设密码
#   - 建完自动验证权限边界（能读写 yindun，不能读系统表/不能建库/不能提权）
#   - 密码不落盘、不写日志

set -uo pipefail

DB_HOST="${1:-}"
ROOT_PASS="${2:-}"
APP_PASS="${3:-Yd2026App#k3}"
DB_NAME="yindun"
DB_USER="yindun"

if [ -z "$DB_HOST" ] || [ -z "$ROOT_PASS" ]; then
  echo "用法: bash $0 <RDS内网地址> <root密码> [新账号密码]" >&2
  exit 2
fi

q() { mysql -h "$DB_HOST" -u"$1" -p"$2" ${3:-} -N -e "$4" 2>&1 | grep -v "Using a password"; }

echo "== 1. 目标实例 $DB_HOST =="
q root "$ROOT_PASS" "" "select version();" | sed 's/^/   MySQL /'

echo "== 2. 建账号 $DB_USER（只授权 $DB_NAME.*）=="
mysql -h "$DB_HOST" -uroot -p"$ROOT_PASS" 2>/dev/null <<SQL
CREATE USER IF NOT EXISTS "$DB_USER"@"%" IDENTIFIED WITH mysql_native_password BY "$APP_PASS";
ALTER USER "$DB_USER"@"%" IDENTIFIED WITH mysql_native_password BY "$APP_PASS";
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, INDEX,
      CREATE TEMPORARY TABLES, LOCK TABLES, CREATE VIEW, SHOW VIEW
  ON \`$DB_NAME\`.* TO "$DB_USER"@"%";
FLUSH PRIVILEGES;
SQL
if [ $? -ne 0 ]; then
  echo "   ✗ 建账号失败，检查 root 密码是否正确" >&2
  exit 1
fi
echo "   ✓ 已建"
q root "$ROOT_PASS" "" "show grants for \"$DB_USER\"@\"%\";" | sed 's/^/   /'

echo "== 3. 验证权限边界 =="
FAIL=0

# 注意：本脚本开了 pipefail，`mysql ... | grep -q` 这种写法会把 mysql 的失败退出码
# 当成整个管道的退出码，即使 grep 匹配成功也判成 false。
# 所以这里一律先取输出到变量，再判断内容。

# 3.1 能读业务表
OUT=$(q "$DB_USER" "$APP_PASS" "$DB_NAME" "select count(*) from yd_alert;" 2>&1)
if echo "$OUT" | grep -qE "denied|ERROR"; then
  echo "   ✗ 读不了业务表：$OUT" ; FAIL=1
else
  echo "   ✓ 能读 $DB_NAME.yd_alert（行数 $(echo "$OUT" | head -1)）"
fi

# 3.2 不能读系统表
OUT=$(q "$DB_USER" "$APP_PASS" "" "select count(*) from mysql.user;" 2>&1)
if echo "$OUT" | grep -q "denied"; then
  echo "   ✓ 读不了 mysql.user（正确）"
else
  echo "   ✗ 竟然能读系统表，权限过大" ; FAIL=1
fi

# 3.3 不能建别的库
OUT=$(q "$DB_USER" "$APP_PASS" "" "create database _should_fail;" 2>&1)
if echo "$OUT" | grep -qE "denied|Access"; then
  echo "   ✓ 建不了其他库（正确）"
else
  q root "$ROOT_PASS" "" "drop database if exists _should_fail;" >/dev/null 2>&1
  echo "   ✗ 竟然能建库，权限过大" ; FAIL=1
fi

# 3.4 不能给自己提权
OUT=$(q "$DB_USER" "$APP_PASS" "" "grant all on *.* to \"$DB_USER\"@\"%\";" 2>&1)
if echo "$OUT" | grep -qE "denied|Access"; then
  echo "   ✓ 不能自我提权（正确）"
else
  echo "   ✗ 竟然能自我提权，严重问题" ; FAIL=1
fi

echo
if [ $FAIL -eq 0 ]; then
  echo "== 完成：所有检查通过 =="
  echo "下一步：把线上 .env 改成"
  echo "   RDS_USER=$DB_USER"
  echo "   RDS_PASS=<你传入的密码>"
  echo "然后 systemctl restart yindun，并用"
  echo "   information_schema.processlist 确认连接用户是 $DB_USER"
  exit 0
else
  echo "== 有检查未通过，先别切 .env ==" >&2
  exit 1
fi
