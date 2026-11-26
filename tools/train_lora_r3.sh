#!/bin/bash
# =====================================================================
# ⚠️ 已作废（2026-11-26），只作历史留档，不要再跑 ⚠️
#
# 两个原因，任何一个都足以让线上接不回来：
#   ① 脚本结尾恢复服务用的那行 `sft_server.py --host 0.0.0.0`
#      ——绑到 0.0.0.0 等于把服务暴露到校园网，且明文违反"8000 只绑 127.0.0.1"；
#   ② 它完全不管反向隧道，恢复完线上第三票是断的（ECS 侧 /health 会报 sft.ok=false）。
# 第四轮用 tools/train_lora_r4.sh；恢复线上统一用
#   bash tools/school_gpu_bringup.sh --restart
# =====================================================================
# =====================================================================
# 银盾 · 第三轮 LoRA 训练（老师 2026-11-23 方案）
#
# 与第二轮的唯一区别是数据（三个正常源 + 两个诈骗源），超参一个不动。
#
# 本脚本额外负责两件事（老师第 4 条要求）：
#   · 训练前停 8000 腾显存
#   · **训练一结束就把 8000 拉回来**，不等人工
#
# 用法：bash ~/yindun/tools/train_lora_r3.sh
# =====================================================================
set -euo pipefail

WORK=$HOME/work/lora1123c
MODEL=$HOME/models/JimmyMa99/AntiFraud-SFT
OUT=$WORK/output
SWIFT=$HOME/venv-swift/bin/swift
PY=$HOME/venv-swift/bin/python3

cd "$WORK"

for f in data/train.jsonl data/val.jsonl; do
  [ -s "$f" ] || { echo "[X] 缺 $f"; exit 1; }
done

# ---- 1. 停 8000，腾显存
if pgrep -f "sft_server.py --host" > /dev/null; then
  echo "[1] 停 8000 以腾显存"
  for pid in $(pgrep -f "sft_server.py --host"); do kill "$pid" 2>/dev/null || true; done
  for i in $(seq 1 20); do
    pgrep -f "sft_server.py --host" > /dev/null || break
    sleep 2
  done
fi
# 显存释放有延迟：8000 刚被杀时还会占着十几 GB，必须等它真放掉
FREE=0
for i in $(seq 1 30); do
  FREE=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)
  [ "$FREE" -ge 30000 ] && break
  sleep 3
done
echo "[i] 显存空闲 ${FREE} MiB"
if [ "$FREE" -lt 30000 ]; then echo "[X] 空闲显存不足 30 GB"; exit 1; fi

echo "[i] train $(wc -l < data/train.jsonl) 条 / val $(wc -l < data/val.jsonl) 条"
echo "[i] 开始 $(date '+%F %T')"

# ---- 2. 训练（超参与第二轮完全一致）
set +e
"$SWIFT" sft \
  --model "$MODEL" \
  --train_type lora \
  --dataset data/train.jsonl \
  --val_dataset data/val.jsonl \
  --split_dataset_ratio 0 \
  --torch_dtype bfloat16 \
  --num_train_epochs 2 \
  --per_device_train_batch_size 2 \
  --per_device_eval_batch_size 2 \
  --gradient_accumulation_steps 8 \
  --learning_rate 1e-4 \
  --lora_rank 32 \
  --lora_alpha 32 \
  --lora_dropout 0.0 \
  --target_modules all-linear \
  --freeze_llm false \
  --freeze_vit true \
  --freeze_aligner true \
  --gradient_checkpointing true \
  --max_length 2048 \
  --loss_scale last_round \
  --warmup_ratio 0.1 \
  --lr_scheduler_type cosine \
  --logging_steps 5 \
  --save_steps 50 \
  --save_total_limit 3 \
  --eval_steps 50 \
  --dataloader_num_workers 2 \
  --dataset_num_proc 4 \
  --output_dir "$OUT" 2>&1 | tee "$WORK/log/train_r3.log"
RC=${PIPESTATUS[0]}
set -e
echo "[i] swift 退出码 $RC，训练结束 $(date '+%F %T')"

# ---- 3. 不管训练成没成，先把 8000 拉回来（老师明确要求）
echo "[3] 恢复 8000 原始服务"
cd "$HOME/yindun"
SFT_DEVICE=cuda ANTIFRAUD_DIR="$MODEL" \
  setsid nohup python3 tools/sft_server.py --host 0.0.0.0 --port 8000 \
  > "$HOME/sft.log" 2>&1 < /dev/null &
for i in $(seq 1 40); do
  H=$(curl -s -m 3 http://127.0.0.1:8000/health 2>/dev/null || true)
  if echo "$H" | grep -q '"loading": *false'; then echo "[4] 8000 已恢复：$H"; break; fi
  sleep 5
done
curl -s -m 3 http://127.0.0.1:8000/health || echo "[X] 8000 仍未起来，看 ~/sft.log"

ls -d "$OUT"/v* 2>/dev/null || true
exit "$RC"
