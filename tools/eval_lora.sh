#!/bin/bash
# =====================================================================
# 训练完 → 加载 LoRA 到 8001 → 用同一份评测集重跑
# 用法：bash eval_lora.sh [checkpoint 路径]
#   不传参则自动取 output/ 下步数最大的 checkpoint
# =====================================================================
set -u
WORK=$HOME/work/lora1123
MODEL=$HOME/models/JimmyMa99/AntiFraud-SFT
PY=$HOME/venv-swift/bin/python3
PORT=8001

cd "$WORK" || exit 1
mkdir -p log eval

# ---- 1. 定位 checkpoint ----
CK="${1:-}"
if [ -z "$CK" ]; then
  CK=$(ls -d "$WORK"/output/*/checkpoint-* 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$CK" ] || [ ! -d "$CK" ]; then
  echo "[X] 找不到 checkpoint（$CK）"; exit 1
fi
CK=$(cd "$CK" && pwd)
echo "[1] LoRA 目录: $CK"
ls "$CK" | head -6

# ---- 2. 确认训练真的结束了，别在训练中抢显存 ----
if pgrep -f "swift sft" > /dev/null; then
  echo "[X] 训练还在跑，等它结束再执行"; exit 1
fi
echo "[2] 训练已结束，显存情况："
nvidia-smi --query-gpu=memory.used,memory.free --format=csv,noheader

# ---- 3. 停掉可能残留的 8000 服务 ----
for pid in $(pgrep -f "tools/sft_server.py"); do
  echo "  停旧服务 pid=$pid"; kill "$pid" 2>/dev/null
done
sleep 3

# ---- 4. 用 venv-swift 起 8001，带 LoRA ----
cd "$HOME/yindun" || exit 1
SFT_DEVICE=cuda \
ANTIFRAUD_DIR="$MODEL" \
SFT_LORA="$CK" \
  setsid nohup "$PY" tools/sft_server.py --host 0.0.0.0 --port $PORT \
  > "$WORK/log/sft8001.log" 2>&1 < /dev/null &

echo "[3] 已启动 8001，等待加载（合并 LoRA 约 1~2 分钟）..."
# 注意：health 里始终带 "error": null，不能用 grep '"error"' 判断失败，
# 必须区分 null 与非空字符串。
for i in $(seq 1 60); do
  H=$(curl -s -m 3 "http://127.0.0.1:$PORT/health" 2>/dev/null)
  ST=$(echo "$H" | "$PY" -c "import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    print('wait'); raise SystemExit
if d.get('loaded'): print('ok')
elif d.get('error'): print('err')
else: print('wait')" 2>/dev/null)
  if [ "$ST" = "ok" ]; then echo "[4] 加载完成"; echo "$H"; break; fi
  if [ "$ST" = "err" ]; then
    echo "[X] 加载失败"; echo "$H"; tail -20 "$WORK/log/sft8001.log"; exit 1
  fi
  sleep 5
done

if ! curl -s -m 3 "http://127.0.0.1:$PORT/health" | grep -q '"loaded": *true'; then
  echo "[X] 5 分钟仍未就绪，看日志："; tail -20 "$WORK/log/sft8001.log"; exit 1
fi

echo "[5] 开始重测（与微调前完全相同的两份数据）"
cd "$WORK" || exit 1
"$PY" eval_judge.py --url "http://127.0.0.1:$PORT" \
  --input data/heldout_cgodial.jsonl --out eval/lora_cgodial.jsonl --workers 3
echo "=== LORA CGODIAL DONE ==="
"$PY" eval_judge.py --url "http://127.0.0.1:$PORT" \
  --holdout holdout.json --out eval/lora_holdout.jsonl --workers 3
echo "=== LORA HOLDOUT DONE ==="
