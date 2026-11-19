#!/usr/bin/env bash
# =====================================================================
# 银盾 · 学校 GPU 容器环境一键恢复（对应改造方案 A7）
# =====================================================================
# 容器被删过一次。这个脚本把 ms-swift 微调环境从零恢复，包括三个
# 不注意就会卡住的坑（都在下面标了原因，别再重新踩一遍）。
#
# 用法（在容器里，不要 sudo）：
#     bash school_gpu_setup.sh              # 装环境
#     bash school_gpu_setup.sh --check      # 只体检，不动任何东西
#
# 实测环境：Python 3.10.0 / torch 2.3.1+cu121 / NVIDIA L40 46G
# 实测时间：约 8 分钟（清华源）
# =====================================================================
set -euo pipefail

VENV="$HOME/venv-swift"
MODEL="${MODEL:-$HOME/models/JimmyMa99/AntiFraud-SFT}"
PIP_INDEX="https://pypi.tuna.tsinghua.edu.cn/simple"
WORK="$HOME/yindun_sft"

# 版本必须锁死，理由见下方注释
SWIFT_VER="3.12.6"
TF_VER="4.48.3"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[注意] %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 体检模式
if [ "${1:-}" = "--check" ]; then
    say "体检"
    echo "python3      $(python3 -V 2>&1)"
    python3 -c "import torch;print('torch        ',torch.__version__,'cuda',torch.cuda.is_available())" 2>/dev/null || echo "torch        未装"
    if [ -x "$VENV/bin/python" ]; then
        "$VENV/bin/python" - <<'PY' 2>/dev/null || true
import swift, transformers, torch, peft
print('swift        ', swift.__version__)
print('transformers ', transformers.__version__)
print('peft         ', peft.__version__)
print('cuda         ', torch.cuda.is_available())
from transformers import Qwen2AudioForConditionalGeneration  # noqa: F401
print('Qwen2Audio   可导入')
PY
        "$VENV/bin/python" -c "import tarfile;assert tarfile.is_tarfile.__name__=='_safe_is_tarfile';print('lzma 补丁     已生效')" 2>/dev/null || warn "lzma 补丁未生效，训练会在 datasets 加载时报错"
    else
        echo "venv         不存在（$VENV）"
    fi
    echo "模型         $(du -sh "$MODEL" 2>/dev/null | cut -f1 || echo '缺失')  $MODEL"
    exit 0
fi

# ---------------------------------------------------------------- 1. 前置检查
say "1/6 检查基础环境"
command -v python3 >/dev/null || die "没有 python3"
PYV=$(python3 -c 'import sys;print("%d.%d"%sys.version_info[:2])')
echo "python3 $PYV"
python3 -c "import torch;assert torch.cuda.is_available()" 2>/dev/null \
    || die "torch/cuda 不可用，先确认容器申请到 GPU"
python3 -c "import torch;print('torch',torch.__version__,'| GPU',torch.cuda.get_device_name(0))"

[ -d "$MODEL" ] || die "模型不存在：$MODEL （需要先把 AntiFraud-SFT 放到这里，约 16G）"
echo "模型 $(du -sh "$MODEL" | cut -f1)"

mkdir -p "$WORK/data" "$WORK/out"

# ---------------------------------------------------------------- 2. venv
# 【坑 1】必须用 --system-site-packages 复用系统 torch。
#   独立 venv 重装 torch 会多下 2.5G，且和容器驱动的 CUDA 版本未必对得上。
#   用 --system-site-packages 时 pip 只往 venv 里装，不会改系统 python3，
#   所以现在跑着的 sft_server.py（transformers 4.49）不受影响。
say "2/6 创建 venv（复用系统 torch）"
if [ -d "$VENV" ]; then
    echo "已存在，跳过"
else
    python3 -m venv --system-site-packages "$VENV"
fi
"$VENV/bin/python" -c "import torch;print('venv 可见 torch',torch.__version__)"

# ---------------------------------------------------------------- 3. 装 ms-swift
# 【坑 2】不能装最新版。
#   ms-swift 4.6.1 要求 transformers 5.x，会连带打乱整个环境。
#   3.12.6 声明 transformers<4.58 可用，但 pip 会挑 4.55.4；
#   而 swift 运行时会明确警告 qwen2_audio 模板要 ">=4.45,<4.49"，
#   所以装完必须手动降到 4.48.3，否则模板不匹配。
say "3/6 安装 ms-swift $SWIFT_VER（约 5 分钟）"
"$VENV/bin/pip" install -q --upgrade pip -i "$PIP_INDEX"
"$VENV/bin/pip" install -q "ms-swift==$SWIFT_VER" -i "$PIP_INDEX"

say "3b/6 降级 transformers 到 $TF_VER 并补音频依赖"
"$VENV/bin/pip" install -q "transformers==$TF_VER" librosa -i "$PIP_INDEX"

# ---------------------------------------------------------------- 4. lzma 补丁
# 【坑 3】Python 3.10.0 的 _lzma.LZMADecompressor 没有 needs_input 属性
#   （实测 hasattr 为 False）。huggingface datasets 加载 jsonl 时会用
#   tarfile.is_tarfile() 逐个探测"是不是压缩包"，走到 xz 分支就抛：
#       AttributeError: '_lzma.LZMADecompressor' object has no attribute 'needs_input'
#   训练数据全是 jsonl，永远不会真去解 tar.xz，探测失败判"不是压缩包"即可。
#   写在 sitecustomize.py 里，venv 每次启动自动生效。
say "4/6 打 lzma 兼容补丁"
SITE=$("$VENV/bin/python" -c "import sysconfig;print(sysconfig.get_paths()['purelib'])")
cat > "$SITE/sitecustomize.py" <<'PY'
# -*- coding: utf-8 -*-
"""Python 3.10.0 的 LZMADecompressor 缺 needs_input，会让 datasets 加载
jsonl 时抛 AttributeError。只兜住这个异常，不影响真正需要 tar 的场景。"""
import tarfile

_orig_is_tarfile = tarfile.is_tarfile


def _safe_is_tarfile(name=None, *args, **kwargs):
    try:
        return _orig_is_tarfile(name, *args, **kwargs)
    except (AttributeError, OSError, EOFError):
        return False


tarfile.is_tarfile = _safe_is_tarfile
PY
"$VENV/bin/python" -c "import tarfile;print('补丁生效:', tarfile.is_tarfile.__name__)"

# ---------------------------------------------------------------- 5. 校验
say "5/6 校验"
"$VENV/bin/python" - <<'PY'
import swift, transformers, torch, peft
print('swift        ', swift.__version__)
print('transformers ', transformers.__version__)
print('peft         ', peft.__version__)
print('cuda         ', torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else '')
from transformers import Qwen2AudioForConditionalGeneration  # noqa: F401
print('Qwen2Audio   可导入')
from swift.llm import TEMPLATE_MAPPING
assert 'qwen2_audio' in TEMPLATE_MAPPING, 'qwen2_audio 模板缺失'
print('qwen2_audio  模板已注册')
PY

# ---------------------------------------------------------------- 6. 提示
say "6/6 完成"
cat <<EOF

目录：
  venv        $VENV
  模型        $MODEL
  数据/输出   $WORK/data  $WORK/out

本地造数据（在笔记本上跑，产物传到 \$WORK/data）：
  python tools/build_sft_data.py --out-dir <目录> --n-pos 300 --n-neg 300
  python tools/split_audio.py   --src <录音目录> --out <目录> --seg 25 --label 否

训练（示例，先跑 3 步验证链路）：
  $VENV/bin/swift sft \\
    --model $MODEL \\
    --train_type lora \\
    --dataset $WORK/data/train.jsonl \\
    --val_dataset $WORK/data/val.jsonl \\
    --template qwen2_audio \\
    --lora_rank 8 --lora_alpha 32 --target_modules all-linear \\
    --freeze_vit true \\
    --max_length 2048 \\
    --per_device_train_batch_size 1 --gradient_accumulation_steps 1 \\
    --num_train_epochs 1 --learning_rate 1e-4 \\
    --output_dir $WORK/out/run1

说明：
  --freeze_vit true       冻结音频编码器，只训 LM（老师方案阶段 D 的硬要求）
  --target_modules        ms-swift 会解析成 ^(language_model\..*\.(q|k|v|o|gate|up|down)_proj)\$
                          实测 448 个 LoRA 张量全部落在 language_model，audio_tower 为 0
  显存                    纯文本 17.7 GiB / 带 25 秒音频 18.1 GiB（L40 46G 充裕）
  速度                    约 1.2~1.5 s/it

再次提醒：用现有数据只能验证"链路通"，不能拿来说效果。
正式训练等真人录音（改造方案阶段 D 第 3 步，不许 TTS）。
EOF
