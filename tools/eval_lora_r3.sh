#!/bin/bash
# =====================================================================
# 第三轮：加载 LoRA → 8001（只绑 127.0.0.1，测完关掉）→ 三套数据两口径重测
#
# 与第二轮的区别（老师 2026-11-23 第 4 条）：
#   · 8001 只绑回环，不对外开放
#   · 不再杀 8000——8000 是原始服务，训练完就该恢复，评测期间一直开着
#   · 测完主动关掉 8001，并校验 8000 仍然健康
#
# 用法：bash eval_lora_r3.sh [checkpoint 路径]
# =====================================================================
set -u
WORK=$HOME/work/lora1123c
MODEL=$HOME/models/JimmyMa99/AntiFraud-SFT
PY=$HOME/venv-swift/bin/python3
PORT=8001

cd "$WORK" || exit 1
mkdir -p log eval

CK="${1:-}"
if [ -z "$CK" ]; then
  CK=$(ls -d "$WORK"/output/*/checkpoint-* 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$CK" ] || [ ! -d "$CK" ]; then echo "[X] 找不到 checkpoint（$CK）"; exit 1; fi
CK=$(cd "$CK" && pwd)
echo "[1] LoRA: $CK"

if pgrep -f "swift sft" > /dev/null; then echo "[X] 训练还在跑"; exit 1; fi
if pgrep -f "port $PORT" > /dev/null; then
  echo "[!] 8001 已有进程，先停"
  for pid in $(pgrep -f "port $PORT"); do kill "$pid" 2>/dev/null; done
  sleep 3
fi

echo "[2] 显存："; nvidia-smi --query-gpu=memory.used,memory.free --format=csv,noheader

cd "$HOME/yindun" || exit 1
SFT_DEVICE=cuda ANTIFRAUD_DIR="$MODEL" SFT_LORA="$CK" \
  setsid nohup "$PY" tools/sft_server.py --host 127.0.0.1 --port $PORT \
  > "$WORK/log/sft8001.log" 2>&1 < /dev/null &

echo "[3] 等 8001 加载（合并 LoRA 约 1~2 分钟）"
OK=0
for i in $(seq 1 72); do
  H=$(curl -s -m 3 "http://127.0.0.1:$PORT/health" 2>/dev/null)
  ST=$(echo "$H" | "$PY" -c "import sys,json
try: d=json.load(sys.stdin)
except Exception: print('wait'); raise SystemExit
if d.get('error'): print('err')
elif d.get('loading'): print('wait')
elif d.get('loadMs'): print('ok')
else: print('wait')" 2>/dev/null)
  if [ "$ST" = "ok" ]; then echo "[4] 加载完成"; echo "$H"; OK=1; break; fi
  if [ "$ST" = "err" ]; then echo "[X] 加载失败"; echo "$H"; tail -20 "$WORK/log/sft8001.log"; exit 1; fi
  sleep 5
done
[ "$OK" = "1" ] || { echo "[X] 6 分钟仍未就绪"; tail -20 "$WORK/log/sft8001.log"; exit 1; }

cd "$WORK" || exit 1
echo "[5] 重测 CGoDial 留出 300"
"$PY" "$HOME/yindun/tools/eval_judge.py" --url "http://127.0.0.1:$PORT" \
  --input data/heldout_cgodial.jsonl --out eval/r3_cgodial.jsonl --workers 3
echo "=== R3 CGODIAL DONE ==="

echo "[6] 重测 holdout 35"
"$PY" "$HOME/yindun/tools/eval_judge.py" --url "http://127.0.0.1:$PORT" \
  --holdout holdout.json --out eval/r3_holdout.jsonl --workers 3
echo "=== R3 HOLDOUT DONE ==="

echo "[7] 重测 30 通真人录音·ASR 文字"
"$PY" "$HOME/yindun/tools/eval_judge.py" --url "http://127.0.0.1:$PORT" \
  --input data/eval30_text.jsonl --out eval/r3_eval30_text.jsonl --workers 3
echo "=== R3 EVAL30 TEXT DONE ==="

echo "[8] 重测 30 通真人录音·音频"
"$PY" "$HOME/yindun/tools/eval_audio.py" --url "http://127.0.0.1:$PORT" \
  --dir audio --out eval/r3_eval30_audio.jsonl --workers 3
echo "=== R3 EVAL30 AUDIO DONE ==="

echo "[9] 关掉 8001"
for pid in $(pgrep -f "port $PORT"); do kill "$pid" 2>/dev/null; done
sleep 3
echo "[10] 校验 8000（原始服务应始终在线）"
curl -s -m 5 http://127.0.0.1:8000/health || echo "[X] 8000 不可用"
echo
echo "=== R3 ALL DONE ==="
