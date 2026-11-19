#!/bin/bash
# migrate_import.sh —— 把 ECS 本机 MySQL 的 yindun 库整套搬到 RDS（只搬数据，不切流量）
# 用法：bash migrate_import.sh <RDS内网IP> <RDS密码>
# 做完只验证，不动 /opt/yindun/.env（切流量是下一步手工做，能做能退）
set -euo pipefail

IP="${1:?用法: bash migrate_import.sh <RDS内网IP> <RDS密码>}"
PASS="${2:?缺 RDS 密码}"
LOCAL_PASS="$(grep ^RDS_PASS /opt/yindun/.env | cut -d= -f2-)"
DUMP="/opt/yindun/backup/yindun-$(date +%Y%m%d-%H%M).sql"

echo "== 1. RDS 连通性"
mysql -h "$IP" -P 3306 -uroot -p"$PASS" --connect-timeout=10 -e "select version() as rds_version;" 2>/dev/null \
  || { echo "✗ 连不上 RDS（安全组没放行 3306，或实例还没 ACTIVE）"; exit 1; }

echo "== 2. 本机备份"
mkdir -p /opt/yindun/backup
mysqldump -uroot -p"$LOCAL_PASS" --single-transaction --routines --triggers --no-tablespaces \
  --set-gtid-purged=OFF --databases yindun > "$DUMP" 2>/dev/null
echo "   $DUMP  ($(du -h "$DUMP" | cut -f1))"

echo "== 3. 导入 RDS"
mysql -h "$IP" -uroot -p"$PASS" < "$DUMP" 2>/dev/null
echo "   导入完成"

echo "== 4. 两边对数"
for T in yd_alert yd_report yd_drill yd_event yd_user yd_known; do
  L=$(mysql -uroot -p"$LOCAL_PASS" -N -e "select count(*) from yindun.$T;" 2>/dev/null)
  R=$(mysql -h "$IP" -uroot -p"$PASS" -N -e "select count(*) from yindun.$T;" 2>/dev/null)
  if [ "$L" = "$R" ]; then echo "   $T  本机=$L  RDS=$R  ✓"; else echo "   $T  本机=$L  RDS=$R  ✗ 不一致"; fi
done

echo "== 5. 走内网耗时（ECS → RDS）"
mysql -h "$IP" -uroot -p"$PASS" -N -e "select 1;" >/dev/null 2>&1 && echo "   连接正常"
echo "完成。没有改 /opt/yindun/.env，线上仍跑本机库。"
