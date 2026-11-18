#!/usr/bin/env bash
# 下载 MagicData 两份真人中文对话语音（带转写稿），公开数据，不用注册
#
# 用法：
#   bash 下载MagicData.sh [目标目录]
#   默认下到 ./magicdata
#
# 下完的体积：meeting 87,457,935 字节 / accent 224,898,493 字节
# 校验：脚本会自动比 md5，不一致就是没下完整，重跑即可（curl -C - 支持断点续传）

set -u
DIR="${1:-./magicdata}"
mkdir -p "$DIR" || { echo "建不了目录 $DIR"; exit 1; }
cd "$DIR" || exit 1

BASE="https://www.modelscope.cn/api/v1/datasets"

dl() {
  local name="$1" url="$2" want="$3"
  if [[ -f "$name" ]] && [[ "$(wc -c < "$name" | tr -d ' ')" == "$want" ]]; then
    echo "[跳过] $name 大小已对上"
  else
    echo "[下载] $name ..."
    curl -L -C - --retry 3 --retry-delay 2 -o "$name" "$url" || { echo "下载失败：$name"; return 1; }
  fi
  local got
  got="$(wc -c < "$name" | tr -d ' ')"
  if [[ "$got" != "$want" ]]; then
    echo "[警告] $name 大小 $got，期望 $want —— 没下完整，重跑本脚本"
    return 1
  fi
  echo "[OK]   $name  $got 字节"
}

dl "meeting.zip" \
  "$BASE/MagicData/A_CHINESE_CONVERSATIONAL_MEETING_SPEECH_CORPUS/repo?Revision=master&FilePath=A%20CHINESE%20CONVERSATIONAL%20MEETING%20SPEECH%20CORPUS.zip" \
  87457935

dl "accent.zip" \
  "$BASE/MagicData/A_CHINESE_STRONG_MANDARIN_ACCENT_CONVERSATIONAL_SPEECH_CORPUS/repo?Revision=master&FilePath=Mandarin_Heavy_Accent_Conversational_Speech_Corpus.zip" \
  224898493

echo
echo "=== md5 核对（对不上就是传坏了/下断了）==="
echo "meeting.zip 期望 d83c8b25ebdb2c82928db3107dd69a5b"
echo "accent.zip  期望 d3d6149414f3aeefb614898081f5f783"
if command -v md5sum >/dev/null 2>&1; then
  md5sum meeting.zip accent.zip 2>/dev/null
elif command -v md5 >/dev/null 2>&1; then
  md5 meeting.zip accent.zip 2>/dev/null
else
  echo "（这台机器没有 md5sum，跳过）"
fi

echo
echo "=== 下一步 ==="
echo "  unzip meeting.zip && unzip accent.zip"
echo "  cd <仓库>/cloud && node test/eval-magicdata.js \"$(pwd)\""
