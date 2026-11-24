#!/usr/bin/env bash
# 学校 GPU 容器 → ECS 反向隧道
#
# 把容器本机的 8000（SFT 服务）映射成 ECS 的 127.0.0.1:8099，
# 于是 ECS 上的 Node 用 SFT_URL=http://127.0.0.1:8099 就能调到学校内网的模型。
#
# 为什么要反向：华为云访问不到学校内网，但学校容器能出外网（22 端口实测可通）。
#
# 关键一行是 -R 的写法：必须显式写 127.0.0.1:8099，不能只写 -R 8099:...。
# ECS 上 tunnel 账号的 authorized_keys 限制了
#   restrict,port-forwarding,permitlisten="127.0.0.1:8099"
# 只允许在本机回环上监听这一个端口。不写 host 时 OpenSSH 会请求监听
# *:8099，与 permitlisten 不匹配，报 “remote port forwarding failed
# for listen port 8099”——2026-11-21 那次就是栽在这里。
#
# 用法（容器上）：
#   nohup bash ~/yindun/tools/sft_tunnel.sh > ~/tunnel.log 2>&1 &
#   tail -f ~/tunnel.log
#
# 环境变量：
#   ECS_HOST  默认 123.249.25.139
#   ECS_PORT  默认 22
#   SFT_PORT  容器上 SFT 服务端口，默认 8000
#   LOCAL_BIND 隧道在 ECS 上监听的回环端口，默认 8099（改了要同步改 authorized_keys）

set -u

ECS_HOST=${ECS_HOST:-123.249.25.139}
ECS_PORT=${ECS_PORT:-22}
SFT_PORT=${SFT_PORT:-8000}
LOCAL_BIND=${LOCAL_BIND:-8099}
KEY=${KEY:-$HOME/.ssh/tunnel_ed25519}

if [ ! -f "$KEY" ]; then
  echo "$(date '+%F %T') 找不到私钥 $KEY，先跑 ssh-keygen -t ed25519 -N '' -f $KEY -C school-gpu-tunnel" >&2
  exit 1
fi

echo "$(date '+%F %T') 隧道守护启动：容器 127.0.0.1:$SFT_PORT -> $ECS_HOST 127.0.0.1:$LOCAL_BIND"

while true; do
  ssh -N -p "$ECS_PORT" -i "$KEY" \
      -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
      -o TCPKeepAlive=yes -o ConnectTimeout=10 \
      -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new \
      -o BatchMode=yes \
      -R "127.0.0.1:$LOCAL_BIND:127.0.0.1:$SFT_PORT" \
      tunnel@"$ECS_HOST"
  rc=$?
  echo "$(date '+%F %T') 隧道断开（rc=$rc），5 秒后重连" >&2
  sleep 5
done
