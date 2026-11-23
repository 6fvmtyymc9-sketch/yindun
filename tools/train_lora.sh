#!/bin/bash
# 银盾 · AntiFraud-SFT 的文字 LoRA 微调（老师《SFT 下一步计划》第二版 第 5 步）
#
# 跑在学校 GPU 容器里，与华为云零交集：不碰线上、不改 SFT_URL。
# 用法：  bash ~/work/lora1123/train_lora.sh
#
# 两条硬性前置（脚本会自己查）：
#   1) 推理服务必须停 —— 它常驻 20+ GB，和训练同时跑会 OOM
#   2) 训练用 ~/venv-swift/bin/swift，不是系统 pip 那个
#
# 超参来源：镜像权重自带 args.json（原始训练用的），只改三处并说明理由：
#   num_train_epochs 10 → 2     老师第二版明确「2,000 条 × 2 epoch」
#   lr 5e-5 → 1e-4              原始是 10 epoch / 33k 条；我们只有 1,900 条 2 epoch，
#                               步数少一个数量级，学习率不抬起来学不到东西
#   max_length 8192 → 2048      文本截到 800 字，2048 token 足够，显存和耗时都省一半
set -euo pipefail

WORK=$HOME/work/lora1123
MODEL=$HOME/models/JimmyMa99/AntiFraud-SFT
OUT=$WORK/output
SWIFT=$HOME/venv-swift/bin/swift

cd "$WORK"

# ---- 前置检查
if pgrep -f "^python3 tools/sft_server.py" > /dev/null; then
  echo "[X] 推理服务还在跑（常驻 20+ GB）。先停它："
  echo "    for pid in \$(pgrep -f '^python3 tools/sft_server.py'); do kill \$pid; done"
  exit 1
fi
FREE=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)
echo "[i] 显存空闲 ${FREE} MiB"
if [ "$FREE" -lt 30000 ]; then
  echo "[X] 空闲显存不足 30 GB，训练会 OOM"
  exit 1
fi
for f in data/train.jsonl data/val.jsonl; do
  [ -s "$f" ] || { echo "[X] 缺 $f"; exit 1; }
done

mkdir -p "$OUT" log
echo "[i] train $(wc -l < data/train.jsonl) 条 / val $(wc -l < data/val.jsonl) 条"
echo "[i] 开始 $(date '+%F %T')，日志 $WORK/log/train.log"

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
  --output_dir "$OUT"

echo "[i] 训练结束 $(date '+%F %T')"
echo "[i] 权重目录："
ls -d "$OUT"/v* 2>/dev/null || ls "$OUT"
