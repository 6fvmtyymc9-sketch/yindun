#!/bin/bash
# 用 macOS 自带语音合成生成三段演示通话录音（8kHz 单声道 mp3，接近真实通话录音的音质）。
# 每段旁边放一份同名 .txt 转写稿：云端没配语音识别时，用它代替识别结果（会标明"演示文本"）。
# 需要：macOS 的 say 命令（中文声音「婷婷」）+ ffmpeg。用法：bash make_calls.sh
set -e
cd "$(dirname "$0")"
TMP=$(mktemp -d)

# 一行一句：角色|台词。角色 A=对方（骗子/来电人），B=老人
make() {
  local out="$1" script="$2" i=0 list="$TMP/list.txt" txt="${1%.*}.txt"
  : > "$list"; : > "$txt"
  while IFS='|' read -r who line; do
    [ -z "$line" ] && continue
    i=$((i+1))
    # 系统默认只装了「婷婷」一个中文声音：两个角色都用她，对方那一方把音调压低来区分
    say -v Tingting -o "$TMP/$i.aiff" "$line"
    if [ "$who" = "A" ]; then
      filt="asetrate=22050*0.82,aresample=22050,atempo=1.2195"
    else
      filt="aresample=22050"
    fi
    ffmpeg -loglevel error -y -i "$TMP/$i.aiff" -af "$filt" -ac 1 "$TMP/$i.wav"
    echo "file '$TMP/$i.wav'" >> "$list"
    printf '%s' "$line" >> "$txt"
  done <<< "$script"
  ffmpeg -loglevel error -y -f concat -safe 0 -i "$list" -ar 8000 -ac 1 -b:a 24k "$out"
  echo "生成 $out（$(du -h "$out" | cut -f1)）"
}

make "13900001111_20261109103015.mp3" "A|喂，您好，请问是王秀英女士吗？
B|是我，你哪位？
A|我这边是市公安局刑侦支队的，警号零三七一。您名下有一张银行卡涉嫌一起洗钱案件，现在需要您配合调查。
B|啊？我没有啊，我什么都不知道。
A|阿姨您别紧张。这个案子是保密的，您先不要告诉家里人，也不要挂电话。
B|那我该怎么办？
A|为了证明您的资金是清白的，您需要把卡里的钱转到我们的安全账户，审查完会原数退还。等一下银行会给您发一个验证码，您念给我听就行。"

make "13700002222_20261109141020.mp3" "A|喂，奶奶，是我呀，听不出来了？
B|你是谁啊？
A|我是小明啊。我手机掉了，这是同学的号。
B|哦哦，小明啊，怎么了？
A|我出了点事，跟人起了冲突，现在急需两万块钱私了。你千万别告诉我爸妈，他们知道会骂死我。
B|这么多钱啊。
A|你先打到我同学的卡上，卡号我一会儿发给你，明天我就还你。"

make "13600003333_20261109160530.mp3" "A|喂，阿姨您好，我是小区物业的小张。
B|哎，你好你好。
A|跟您说一下，明天上午九点到十一点小区停水检修，您提前接点水。
B|好的，知道了。
A|还有您家的快递放门卫了，有空下来拿一下。谢谢阿姨。"

rm -rf "$TMP"
