#!/bin/bash
# =====================================================================
# 银盾 · 第四轮 LoRA 评测（老师 2026-11-26 方案 §3.8 第 1~3、5 步）
#
# 只做"不碰线上"的部分：起 8001 评测实例 → 跑三组数据 → 统计 → 关 8001。
# 第 4 步（产品链路 TAF-200 换 8000 模型）风险大，单独手工做，脚本里不做。
#
# ⚠️ 三条纪律（都是踩过坑换来的）：
#   ① 8001 只绑 127.0.0.1，测完必须关，否则和线上 8000 抢显存
#   ② 起 8001 **不要用 --restart**：那会连带重建隧道，把线上第三票打断
#   ③ 评测前先确认 8000 已经恢复（训练脚本末尾会自动恢复）；两个实例同开约 40G/46G
#
# 用法：
#   bash ~/yindun/tools/eval_lora_r4.sh
#   LORA=/path/to/checkpoint-xxx bash ~/yindun/tools/eval_lora_r4.sh
# =====================================================================
set -uo pipefail

PORT="${PORT:-8001}"
OUT="${OUT:-$HOME/work/lora_r4/eval}"
LORA="${LORA:-$(cat "$HOME/work/lora_r4/log/lora_dir.txt" 2>/dev/null || true)}"
SFT_MAX_NEW="${SFT_MAX_NEW:-768}"
IDX30="$HOME/work/lora_r4/eval30_idx.jsonl"
TXT30="$HOME/work/lora_r4/eval30_txt.jsonl"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m[ok] %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[注意] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

say "0/5 前置检查"
[ -n "$LORA" ] && [ -d "$LORA" ] || die "LoRA 目录不存在：'${LORA:-空}'（训练没跑完？看 ~/work/lora_r4/log/lora_dir.txt）"
ok "LoRA: $LORA"
[ -f "$IDX30" ] || die "缺 $IDX30"
[ -f "$TXT30" ] || die "缺 $TXT30"
mkdir -p "$OUT"

# 线上 8000 必须在（评测要拿它当对照，也是纪律③）
if curl -s -m 5 "http://127.0.0.1:8000/health" | grep -q '"ok": true'; then
    ok "线上 8000 在跑"
else
    warn "8000 不在跑——按纪律③本该先恢复线上，但评测不依赖 8000，继续"
fi

# 先确认 8001 没被别的进程占着
if pgrep -f "[t]ools/sft_server\.py.*--port $PORT( |$)" >/dev/null 2>&1; then
    warn "8001 已经有实例在跑，先关掉再起（避免加载的不是当前 LoRA）"
    for p in $(pgrep -f "[t]ools/sft_server\.py.*--port $PORT( |$)"); do kill "$p" 2>/dev/null; done
    sleep 6
fi

say "1/5 起 $PORT（绑 127.0.0.1，不重建隧道）"
cd "$HOME/yindun" || die "进不去 ~/yindun"
SFT_PORT="$PORT" SFT_LORA="$LORA" SFT_MAX_NEW="$SFT_MAX_NEW" \
    bash tools/school_gpu_bringup.sh || die "起 $PORT 失败"
H=$(curl -s -m 10 "http://127.0.0.1:$PORT/health")
echo "$H" | grep -q '"ok": true' || die "$PORT 健康检查不过：$H"
ok "$PORT 就绪：$(echo "$H" | head -c 160)"
# 必须确认加载的确实是 LoRA，而不是悄悄退回了原始模型
echo "$H" | grep -q "$(basename "$LORA")" \
    && ok "已确认加载 $LORA" \
    || warn "health 里没看到 LoRA 目录名，人工核对：$H"

say "2/5 TAF-200 音频（200 条，约 15~20 分钟）"
"$HOME/venv-swift/bin/python" tools/eval_audio_index.py \
    --url "http://127.0.0.1:$PORT" --dir "$HOME/taf/taf_audio" \
    --index "$HOME/taf200/index.jsonl" --out "$OUT/taf200_audio_lora.jsonl" --workers 3 \
    || warn "TAF-200 评测有失败条数"

say "3/5 真人 30 通音频"
"$HOME/venv-swift/bin/python" tools/eval_audio_index.py \
    --url "http://127.0.0.1:$PORT" --dir "$HOME/yindun/eval30" \
    --index "$IDX30" --out "$OUT/real30_audio_lora.jsonl" --workers 3 \
    || warn "真人音频评测有失败条数"

say "4/5 真人 30 通文字"
"$HOME/venv-swift/bin/python" tools/eval_judge.py \
    --url "http://127.0.0.1:$PORT" --input "$TXT30" \
    --out "$OUT/real30_text_lora.jsonl" --workers 3 \
    || warn "真人文字评测有失败条数"

say "5/5 统计与 §3.9 判据"
python3 tools/stat_lora_r4.py taf200 --lora "$OUT/taf200_audio_lora.jsonl"
RC1=$?
python3 tools/stat_lora_r4.py real30 --lora "$OUT/real30_audio_lora.jsonl" --mode audio
RC2=$?
python3 tools/stat_lora_r4.py real30 --lora "$OUT/real30_text_lora.jsonl" --mode text

# 关掉 8001（纪律①）
say "收尾：关闭 $PORT"
for p in $(pgrep -f "[t]ools/sft_server\.py.*--port $PORT( |$)" 2>/dev/null); do kill "$p" 2>/dev/null; done
sleep 5
pgrep -f "[t]ools/sft_server\.py.*--port $PORT( |$)" >/dev/null 2>&1 \
    && warn "$PORT 还在，手工 kill" || ok "$PORT 已关闭"
nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader

echo
if [ "$RC1" -eq 0 ] && [ "$RC2" -eq 0 ]; then
    echo "✅ 第 1~3 步全过。还剩第 4 步：产品链路 TAF-200（换 8000 的模型，跑完立刻换回）"
else
    echo "❌ 有判据未过（TAF-200 RC=$RC1 / 真人 RC=$RC2）。按计划 §3.8「前一步不过就不往下」，"
    echo "   不要做第 4 步；报告如实写，线上继续用原始模型。"
fi
