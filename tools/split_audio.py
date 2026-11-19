#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
长录音切片 → ms-swift 音频训练样本
====================================================================
【为什么必须切】
Qwen2-Audio 的音频编码器位置编码上限 1500 帧，按 40 帧/秒算约 **37 秒**。
MagicData 那批每段是 1200+ 秒的整场会议录音，直接喂进去只会被截断，
模型听到的是开头 37 秒，后面的内容全部丢掉 —— 训练和评估都会失真。

线上真实场景也是一通电话几十秒，切成 25 秒段落反而更贴近部署条件。

【产出】
  <out>/<原名>_<序号>.wav      切片音频
  <out>/audio.jsonl            每行一条 ms-swift 样本，audios 指向上面的切片

用法：
    python split_audio.py --src <音频目录> --out <输出目录> \
        --seg 25 --per-file 12 --label 否
    # 诈骗样本用 --label 是
"""

import argparse
import glob
import io
import json
import os
import re
import sys
import wave

# 与 tools/sft_server.py 的 AUDIO_PROMPT 逐字一致
AUDIO_PROMPT = (
    "这是一段电话通话录音。请判断打电话的人是不是在实施诈骗，"
    "只回答一个字：是，或者否。不要解释。"
)

# Qwen2-Audio 音频编码器上限 1500 帧 ≈ 37 秒；25 秒留足余量
MAX_SEG = 30.0


def slice_one(src, out_dir, seg, per_file, prefix=""):
    """切一个文件。返回切片路径列表。"""
    made = []
    base = os.path.splitext(os.path.basename(src))[0]
    try:
        w = wave.open(src, "rb")
    except Exception as e:
        print(f"  [跳过] {src}: {e}", file=sys.stderr)
        return made

    try:
        fr, n = w.getframerate(), w.getnframes()
        ch, sw = w.getnchannels(), w.getsampwidth()
        step = int(fr * seg)
        total = min(per_file, (n + step - 1) // step)
        for i in range(total):
            start = i * step
            cnt = min(step, n - start)
            if cnt < fr * 3:          # 尾巴不足 3 秒，丢掉
                continue
            w.setpos(start)
            data = w.readframes(cnt)
            path = os.path.join(out_dir, f"{prefix}{base}_{i:03d}.wav")
            with wave.open(path, "wb") as o:
                o.setnchannels(ch)
                o.setsampwidth(sw)
                o.setframerate(fr)
                o.writeframes(data)
            made.append(path)
    finally:
        w.close()
    return made


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="音频目录（递归找 wav）")
    ap.add_argument("--out", required=True)
    ap.add_argument("--seg", type=float, default=25.0, help="每段秒数，勿超 30")
    ap.add_argument("--per-file", type=int, default=12, help="每个文件最多取几段")
    ap.add_argument("--label", default="否", choices=["是", "否"])
    ap.add_argument("--prefix", default="", help="切片文件名前缀，避免同名覆盖")
    args = ap.parse_args()

    if args.seg > MAX_SEG:
        print(f"⚠️ seg={args.seg} 超过 {MAX_SEG}s，会被模型截断", file=sys.stderr)

    os.makedirs(args.out, exist_ok=True)
    files = sorted(glob.glob(os.path.join(args.src, "**", "*.wav"), recursive=True))
    if not files:
        print("没找到 wav", file=sys.stderr)
        sys.exit(1)

    all_clips = []
    for f in files:
        clips = slice_one(f, args.out, args.seg, args.per_file, args.prefix)
        print(f"  {os.path.basename(f)} → {len(clips)} 段")
        all_clips.extend(clips)

    out_jsonl = os.path.join(args.out, "audio.jsonl")
    with io.open(out_jsonl, "w", encoding="utf-8") as w:
        for p in all_clips:
            rec = {
                "messages": [
                    {"role": "user", "content": AUDIO_PROMPT + "\n\n<audio>"},
                    {"role": "assistant", "content": args.label},
                ],
                "audios": [os.path.abspath(p)],
            }
            w.write(json.dumps(rec, ensure_ascii=False) + "\n")

    print(f"\n切片合计 {len(all_clips)} 条，label={args.label}")
    print(f"jsonl  {out_jsonl}")


if __name__ == "__main__":
    main()
