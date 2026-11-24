#!/usr/bin/env bash
# =====================================================================
# 银盾 · 学校 GPU 容器「容器重建后一条命令恢复线上可用」
# =====================================================================
# 与 school_gpu_setup.sh 的分工：
#   school_gpu_setup.sh   恢复**训练**环境（ms-swift / peft / torch），约 8 分钟
#   本脚本                 恢复**线上服务**：SFT 推理服务 + 反向隧道 + 自检
#
# 老师《SFT接入计划》A7 要的是后者：容器被重建后，一条命令把
# 依赖、模型权重、SFT 服务、隧道全部拉起来，并在干净容器上真跑过一遍。
#
# 用法（在容器里，不要 sudo）：
#   bash ~/yindun/tools/school_gpu_bringup.sh            # 拉起
#   bash ~/yindun/tools/school_gpu_bringup.sh --check    # 只体检，不动任何东西
#   bash ~/yindun/tools/school_gpu_bringup.sh --restart  # 服务与隧道都强制重启
#
# 幂等：已经跑着的服务/隧道不会重复起（除非 --restart）。
# =====================================================================
set -uo pipefail

WORK="${WORK:-$HOME/yindun}"
MODEL="${MODEL:-$HOME/models/JimmyMa99/AntiFraud-SFT}"
VENV="${VENV:-$HOME/venv-swift}"
SFT_PORT="${SFT_PORT:-8000}"
ECS_HOST="${ECS_HOST:-123.249.25.139}"
TUNNEL_LOG="${TUNNEL_LOG:-$HOME/tunnel.log}"
SFT_LOG="${SFT_LOG:-$HOME/sft.log}"

MODE="${1:-up}"
say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m[ok] %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[注意] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

# 判服务是否在跑：用 -N 看连通，不要用宽泛的 pgrep -f sft_server
sft_pid() { pgrep -f "^python3 tools/sft_server.py" 2>/dev/null | head -1; }

# 隧道有两层进程：守护脚本（循环重连）+ ssh -N（真正建隧道）。
# 两个坑，都实际踩过：
#   ① ssh -N 的命令行里**不含** sft_tunnel 字样，只按脚本名杀不掉它；
#   ② 只杀 ssh -N 不杀守护脚本，5 秒后自己重连，于是新旧两套抢同一个端口，
#      抢输的那套永远在日志里刷 "remote port forwarding failed for listen port 8099"。
#      —— 2026-11-21 那次隧道"起不来"，以及 11-24 20:13 的告警，都是这个。
# 匹配串写成 [s]ft_tunnel / [1]27... ，否则 pgrep 会匹配到执行它的这个 shell 自己。
TUN_GUARD_PAT="[s]ft_tunnel\.sh"
TUN_SSH_PAT="[1]27\.0\.0\.1:8099:127\.0\.0\.1:"
tun_alive() { pgrep -f "$TUN_SSH_PAT" >/dev/null 2>&1; }
tun_guard_count() { pgrep -f "$TUN_GUARD_PAT" 2>/dev/null | wc -l | tr -d ' '; }
# 顺序不能反：先杀守护（断了重连的念头），再杀 ssh。反了就会被重连抢回端口
tun_kill_all() {
    local p
    for p in $(pgrep -f "$TUN_GUARD_PAT" 2>/dev/null); do kill "$p" 2>/dev/null; done
    sleep 1
    for p in $(pgrep -f "$TUN_SSH_PAT" 2>/dev/null); do kill "$p" 2>/dev/null; done
    sleep 1
}

health_json() { curl -s -m 6 "http://127.0.0.1:$SFT_PORT/health" 2>/dev/null; }

# ---------------------------------------------------------------- 体检
check_only() {
    say "体检"
    echo "python3      $(python3 -V 2>&1)"
    python3 -c "import torch;print('torch        ',torch.__version__,'cuda',torch.cuda.is_available())" 2>/dev/null \
        || echo "torch        未装"
    nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv,noheader 2>/dev/null \
        || warn "nvidia-smi 不可用"
    echo "模型权重      $(du -sh "$MODEL" 2>/dev/null | cut -f1 || echo '缺失')  $MODEL"
    echo "仓库          $([ -d "$WORK" ] && echo "$WORK" || echo '缺失')"
    echo "SFT 服务      pid=$(sft_pid || echo 无)  $(health_json | head -c 120)"
    echo "隧道          $(tun_alive && echo "ssh 在 / 守护 $(tun_guard_count) 份" || echo 不在)"
    echo "隧道日志      $(tail -2 "$TUNNEL_LOG" 2>/dev/null | tr '\n' ' ')"
}

if [ "$MODE" = "--check" ]; then
    check_only
    exit 0
fi

# ---------------------------------------------------------------- 前置
say "0/4 前置检查"
[ -d "$WORK" ] || die "仓库不在 $WORK（先 git clone 并切到含本脚本的提交）"
[ -f "$WORK/tools/sft_server.py" ] || die "缺少 $WORK/tools/sft_server.py"
[ -f "$WORK/tools/sft_tunnel.sh" ] || die "缺少 $WORK/tools/sft_tunnel.sh"
[ -d "$MODEL" ] || die "模型权重不在 $MODEL（约 16G，需要先放进来）"
[ -f "$HOME/.ssh/tunnel_ed25519" ] || die "缺少隧道私钥 ~/.ssh/tunnel_ed25519"
python3 -c "import torch" 2>/dev/null || die "torch 未装，先跑 bash $WORK/tools/school_gpu_setup.sh"
ok "仓库、权重、密钥、torch 都在"

# ---------------------------------------------------------------- 服务
say "1/4 SFT 推理服务（127.0.0.1:$SFT_PORT）"
if [ "$MODE" = "--restart" ] && [ -n "$(sft_pid)" ]; then
    kill "$(sft_pid)" 2>/dev/null; sleep 3
fi

if [ -n "$(sft_pid)" ]; then
    ok "已在运行 pid=$(sft_pid)，不重启"
else
    cd "$WORK" || die "进不去 $WORK"
    SFT_DEVICE=cuda ANTIFRAUD_DIR="$MODEL" \
        setsid nohup python3 tools/sft_server.py --host 127.0.0.1 --port "$SFT_PORT" \
        > "$SFT_LOG" 2>&1 < /dev/null &
    echo "  已启动，等模型加载（约 9 秒）…"
fi

# 等 health。权重加载实测 8.3 秒，冷启动磁盘慢时给到 90 秒
for i in $(seq 1 45); do
    h=$(health_json)
    if echo "$h" | grep -q '"ok": true'; then
        ok "服务就绪：$h"
        break
    fi
    sleep 2
    [ "$i" -eq 45 ] && { tail -20 "$SFT_LOG" 2>/dev/null; die "90 秒内没起来，看 $SFT_LOG"; }
done

# ---------------------------------------------------------------- 隧道
say "2/4 反向隧道（$ECS_HOST 127.0.0.1:8099 -> 本机 $SFT_PORT）"
need_tunnel=0
if [ "$MODE" = "--restart" ]; then
    need_tunnel=1
elif ! tun_alive; then
    need_tunnel=1
fi
# 守护脚本多于 1 个 = 上次没清干净，必有一份在抢端口失败，全清再起
gc=$(tun_guard_count)
if [ "$gc" -gt 1 ]; then
    warn "有 $gc 个隧道守护脚本（应恰好 1 个），清理后重起"
    need_tunnel=1
fi

if [ "$need_tunnel" = "1" ]; then
    tun_kill_all
    cd "$WORK" || die "进不去 $WORK"
    setsid nohup bash tools/sft_tunnel.sh > "$TUNNEL_LOG" 2>&1 < /dev/null &
    sleep 5
    ok "已启动，日志 $TUNNEL_LOG"
else
    ok "已在运行且只有一份守护，不重启"
fi

# ---------------------------------------------------------------- 自检
say "3/4 自检"
h=$(health_json)
echo "$h" | grep -q '"ok": true' && ok "容器本机 /health 通" || warn "容器本机 /health 不通"
tun_alive && ok "隧道 ssh 进程在" || warn "隧道 ssh 进程不在"
gc=$(tun_guard_count)
if [ "$gc" = "1" ]; then ok "守护脚本 1 份"; else warn "守护脚本 $gc 份（应恰好 1 份）"; fi
if grep -q "remote port forwarding failed" "$TUNNEL_LOG" 2>/dev/null; then
    warn "隧道日志里有端口转发失败——多见于两套守护抢端口，或 -R 没显式写 127.0.0.1:8099"
    warn "（permitlisten 只认 127.0.0.1:8099 这一个，不写 host 会被拒）"
fi

say "4/4 下一步（ECS 侧验证，在 ECS 上执行）"
cat <<EOF
  curl -s 127.0.0.1:8099/health          # 应返回 "ok": true
  curl -s 127.0.0.1:8080/api/health      # 应看到 "sft":{"enabled":true,...,"ok":true}
  curl -s 127.0.0.1:8080/api/callaudio/ping   # sft.ready 应为 true

把学校服务停掉复盘仍要能出结果（降级两票），恢复后不用重启 ECS：
  在容器上 kill 掉隧道进程（本脚本 --check 会打印它的 pid），
  然后 ECS 跑一通录音，sftState 应是"超时或失败"；
  重新执行本脚本拉起隧道，再跑一通，sftState 应回到"已判定"。
EOF
