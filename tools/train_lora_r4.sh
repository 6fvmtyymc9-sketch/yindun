#!/bin/bash
# =====================================================================
# 银盾 · 第四轮 LoRA 训练（老师 2026-11-26 方案 §3.6）
#
# 与第三轮（train_lora_r3.sh，已作废）的差别只有三处：
#   ① 工作目录 ~/work/lora_r4
#   ② **恢复线上统一走 school_gpu_bringup.sh --restart**
#      （r3 结尾那行 `--host 0.0.0.0` 把服务暴露到校园网，且不管反向隧道，
#        照抄会让线上第三票直接断掉）
#   ③ 日志 ~/work/lora_r4/log/train_r4.log
#
# 超参与第三轮**完全一致**，保证可对比：
#   lora rank32/alpha32 · all-linear · 冻结 vit+aligner · loss_scale last_round
#   lr 1e-4 cosine warmup 0.1 · 2 epoch · batch 2×8 · max_length 2048
#
# ⚠️ 两个必须守住的口径：
#   ① 线上 SFT_MAX_NEW 现在是 **768**（计划 3.10，超时率 8%→1.5%）。
#      sft_server.py 的默认值是 1536，恢复时不显式传 768 就会退回去，
#      超时率重新变 8%，且 health 里看不出来。本脚本默认值即 768。
#   ② 训完只恢复**原始模型**。LoRA 要不要上，等 §3.8 评测通过再单独切。
#
# 用法（容器里）：
#   nohup bash ~/yindun/tools/train_lora_r4.sh > ~/work/lora_r4/log/run.log 2>&1 &
#   训前先在群里说一声：训练期间 8000 停掉，线上降级为两票（功能不坏，只是没有第三票）
# =====================================================================
set -uo pipefail

WORK="${WORK:-$HOME/work/lora_r4}"
MODEL="${MODEL:-$HOME/models/JimmyMa99/AntiFraud-SFT}"
OUT="${OUT:-$WORK/output}"
SWIFT="$HOME/venv-swift/bin/swift"
PY="$HOME/venv-swift/bin/python3"
YINDUN="$HOME/yindun"
SFT_PORT="${SFT_PORT:-8000}"
# 第二轮（老师方案：第二次训练只改一处，优先把学习率降到 5e-5）
LR="${LR:-1e-4}"
# 恢复线上时必须保持 768（见文件头 ⚠️ ①）
export SFT_MAX_NEW="${SFT_MAX_NEW:-768}"
LOG="$WORK/log/train_r4.log"

mkdir -p "$WORK/log"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m[ok] %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[注意] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

# ------------------------------------------------------------------ 1. 数据与验收 gate
say "1/5 数据检查与九项验收"
cd "$WORK" || die "进不去 $WORK"
for f in data/train.jsonl data/val.jsonl data/summary.json; do
    [ -s "$f" ] || die "缺 $f（先跑 tools/build_sft_data_r4.py）"
done

"$PY" - <<'EOF' || exit 1
import json, os, sys
p = os.path.expanduser("~/work/lora_r4/data/summary.json")
d = json.load(open(p, encoding="utf-8"))
bad = {k: v for k, v in d["checks"].items() if not v["pass"]}
print(f"[i] 验收：{len(d['checks']) - len(bad)}/{len(d['checks'])} 项通过"
      f"（train {d['train']} / val {d['val']}）")
if bad:
    for k, v in bad.items():
        print(f"    ❌ {k}: {v['value']}（要求 {v['require']}）")
    print("[X] 九项未全过，按计划不能开训")
    sys.exit(1)
EOF
ok "九项全过"

echo "[i] train $(wc -l < data/train.jsonl) 条 / val $(wc -l < data/val.jsonl) 条"
echo "[i] SFT_MAX_NEW=$SFT_MAX_NEW（训练结束后恢复线上沿用它）"

# ------------------------------------------------------------------ 2. 停 8000 腾显存
say "2/5 停 8000 腾显存（训练期间线上降级为两票）"
# 精确匹配 --port 8000：同时开着 8001 评测实例时不能杀错
PIDS=$(pgrep -f "[t]ools/sft_server\.py.*--port $SFT_PORT( |$)" 2>/dev/null || true)
if [ -n "$PIDS" ]; then
    for p in $PIDS; do kill "$p" 2>/dev/null || true; done
    for i in $(seq 1 20); do
        pgrep -f "[t]ools/sft_server\.py.*--port $SFT_PORT( |$)" >/dev/null 2>&1 || break
        sleep 2
    done
    ok "已停 pid=$PIDS"
else
    warn "8000 本来就没在跑"
fi

FREE=0
for i in $(seq 1 30); do
    FREE=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)
    [ "$FREE" -ge 30000 ] && break
    sleep 3
done
echo "[i] 显存空闲 ${FREE} MiB"
[ "$FREE" -ge 30000 ] || die "空闲显存不足 30 GB，训练会 OOM"

# ------------------------------------------------------------------ 3. 训练
say "3/5 训练开始 $(date '+%F %T')"
T0=$(date +%s)

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
  --learning_rate "$LR" \
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
  --output_dir "$OUT" 2>&1 | tee "$LOG"
RC=${PIPESTATUS[0]}
EL=$(( $(date +%s) - T0 ))
echo "[i] swift 退出码 $RC，训练结束 $(date '+%F %T')，耗时 $((EL/60)) 分钟"

LORA=''
CKPT=$(ls -d "$OUT"/v*/checkpoint-* 2>/dev/null | sort -V | tail -1 || true)
[ -n "$CKPT" ] && LORA="${CKPT%/checkpoint-*}"
echo "[i] LoRA 目录：${LORA:-（没找到，看 $LOG）}"
echo "$LORA" > "$WORK/log/lora_dir.txt"

# ------------------------------------------------------------------ 4. 恢复线上（原始模型 + 768）
say "4/5 恢复线上（原始模型，SFT_MAX_NEW=$SFT_MAX_NEW）"
cd "$YINDUN" || die "进不去 $YINDUN"
SFT_MAX_NEW="$SFT_MAX_NEW" bash tools/school_gpu_bringup.sh --restart \
    || die "bringup --restart 失败，线上现在没有第三票，立刻手工处理"

# ------------------------------------------------------------------ 5. 自检
say "5/5 自检"
SFT_MAX_NEW="$SFT_MAX_NEW" bash tools/school_gpu_bringup.sh --check

echo
if [ "$RC" -ne 0 ]; then
    warn "训练退出码非 0（$RC），先看 $LOG 再决定是否重训"
fi
cat <<EOF

下一步（按计划 §3.8，前一步不过就不往下）：
  1) 用训练出的 LoRA 起**评测**实例，只绑回环、不碰线上 8000：
     SFT_PORT=8001 SFT_LORA=$LORA SFT_MAX_NEW=$SFT_MAX_NEW \\
       bash ~/yindun/tools/school_gpu_bringup.sh
  2) 跑 TAF-200 音频：~/venv-swift/bin/python $YINDUN/tools/eval_audio_index.py \\
       --url http://127.0.0.1:8001 --dir ~/taf/taf_audio \\
       --index ~/taf200/index.jsonl --out ~/work/lora_r4/eval/taf200_audio.jsonl --workers 3
  3) 对比基线（原始模型 768：p>=0.85 召回 94/100、误报 12/100）：
     ~/venv-swift/bin/python $YINDUN/tools/stat_product_taf200.py 同口径脚本
  4) **测完把 8001 关掉**：kill \$(pgrep -f '[t]ools/sft_server\.py.*--port 8001')
     —— 8001 只用于评测，常开会和线上抢显存
EOF
exit "$RC"
