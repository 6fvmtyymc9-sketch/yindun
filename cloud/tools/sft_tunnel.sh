#!/usr/bin/env bash
# 学校容器 → ECS 反向隧道：把本机 8000 映射成 ECS 的 127.0.0.1:8099
# 用法：nohup bash ~/yindun/tools/sft_tunnel.sh > ~/tunnel.log 2>&1 &
# 停止：pkill -f sft_tunnel.sh
ECS_HOST=${ECS_HOST:-123.249.25.139}
ECS_PORT=${ECS_PORT:-22}
LOCAL_PORT=${LOCAL_PORT:-8000}
REMOTE_PORT=${REMOTE_PORT:-8099}
KEY=${KEY:-$HOME/.ssh/tunnel_ed25519}

if [ ! -f "$KEY" ]; then
  echo "找不到隧道私钥 $KEY，请先生成：ssh-keygen -t ed25519 -N '' -f $KEY -C school-gpu-tunnel" >&2
  exit 1
fi

echo "$(date '+%F %T') 隧道守护启动：127.0.0.1:$LOCAL_PORT -> $ECS_HOST:$REMOTE_PORT"
while true; do
  ssh -N -p "$ECS_PORT" -i "$KEY" \
      -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
      -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new \
      -o BatchMode=yes \
      -R "127.0.0.1:${REMOTE_PORT}:127.0.0.1:${LOCAL_PORT}" tunnel@"$ECS_HOST"
  echo "$(date '+%F %T') 隧道断开，5 秒后重连" >&2
  sleep 5
done
