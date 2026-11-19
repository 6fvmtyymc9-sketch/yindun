#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MP3 帧头解析：判断一段音频是不是「真实电话录音」。

为什么需要它：
    本机没有 ffmpeg / librosa，但判断「是不是电话录音」根本不需要解码音频，
    只看 MP3 帧头里的采样率、声道数、比特率就够了。

    真实电话的物理约束：
        传统电话 PSTN/GSM  → 8000 Hz 单声道（窄带，3.4kHz 以上被砍）
        VoLTE / 微信语音    → 16000 Hz 单声道（宽带，上限 7kHz）
    48 kHz 立体声只出现在录音棚、音乐、TTS 合成里。
    所以「48000 Hz + 立体声」基本可以直接判定：这不是一通真实电话。

用法：
    python probe_mp3.py a.mp3 b.mp3
    python probe_mp3.py "D:/下载/*.mp3"      # 支持通配符

输出：每个文件的采样率、声道、比特率、估算时长、以及检测到的编码器标记。
"""

import glob
import os
import sys

# MPEG1 层 III 比特率表（kbps）
BITRATE_V1_L3 = [None, 32, 40, 48, 56, 64, 80, 96,
                 112, 128, 160, 192, 224, 256, 320, None]
# MPEG2 / MPEG2.5 层 III 比特率表
BITRATE_V2_L3 = [None, 8, 16, 24, 32, 40, 48, 56,
                 64, 80, 96, 112, 128, 144, 160, None]
SAMPLE_RATE_V1 = [44100, 48000, 32000, None]
SAMPLE_RATE_V2 = [22050, 24000, 16000, None]
CHANNEL_MODE = ["立体声", "联合立体声", "双声道", "单声道"]
ENCODER_MARKS = [b"ChatTTS", b"Lavf", b"LAME", b"Adobe", b"Audition", b"ffmpeg"]

# 电话信道的采样率白名单
TELEPHONY_RATES = (8000, 16000)


def find_frame(buf):
    """跳过 ID3v2 标签，找到第一个 MPEG 帧头。"""
    i = 0
    if buf[:3] == b"ID3" and len(buf) >= 10:
        size = ((buf[6] & 0x7F) << 21) | ((buf[7] & 0x7F) << 14) \
             | ((buf[8] & 0x7F) << 7) | (buf[9] & 0x7F)
        i = 10 + size
    while i < len(buf) - 4:
        if buf[i] == 0xFF and (buf[i + 1] & 0xE0) == 0xE0:
            h = buf[i:i + 4]
            ver = (h[1] >> 3) & 3          # 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
            layer_bits = (h[1] >> 1) & 3   # 1 = Layer III
            br_idx = (h[2] >> 4) & 0xF
            sr_idx = (h[2] >> 2) & 3
            mode = (h[3] >> 6) & 3
            if layer_bits == 0 or br_idx in (0, 15) or sr_idx == 3:
                i += 1
                continue
            layer = 4 - layer_bits
            if ver == 3:
                br = BITRATE_V1_L3[br_idx] if layer == 3 else None
                sr = SAMPLE_RATE_V1[sr_idx]
            else:
                br = BITRATE_V2_L3[br_idx] if layer == 3 else None
                sr = SAMPLE_RATE_V2[sr_idx]
            return {
                "version": {3: "MPEG1", 2: "MPEG2", 0: "MPEG2.5"}.get(ver, ver),
                "layer": layer,
                "bitrate_kbps": br,
                "sample_rate": sr,
                "channels": CHANNEL_MODE[mode],
                "offset": i,
            }
        i += 1
    return None


def probe(path):
    data = open(path, "rb").read()
    info = find_frame(data)
    if not info:
        return None
    size = len(data)
    br = info["bitrate_kbps"]
    dur = (size * 8) / (br * 1000) if br else None
    info["bytes"] = size
    info["duration_sec"] = dur
    info["marks"] = [m.decode() for m in ENCODER_MARKS if m in data[:400000]]

    # 判定：能不能是一通真实电话
    mono = info["channels"] in ("单声道", "双声道")
    tele_rate = info["sample_rate"] in TELEPHONY_RATES
    if mono and tele_rate:
        info["verdict"] = "★ 符合电话信道特征（单声道 + 8k/16k）"
    elif not tele_rate:
        info["verdict"] = "✗ 非电话信道（%d Hz 不是通话采样率）" % (info["sample_rate"] or -1)
    else:
        info["verdict"] = "? 采样率像电话，但声道存疑（%s）" % info["channels"]
    return info


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    paths = []
    for a in argv[1:]:
        hits = glob.glob(a)
        paths.extend(hits if hits else [a])

    for p in paths:
        if not os.path.isfile(p):
            print(f"{p}\n   文件不存在")
            continue
        info = probe(p)
        print(os.path.basename(p))
        if not info:
            print("   无法解析 MP3 帧头（可能不是 MP3，或已损坏）")
            continue
        dur = f"{info['duration_sec']:.1f} 秒" if info["duration_sec"] else "未知"
        print(f"   {info['bytes']:,} B | {info['version']} Layer{info['layer']} | "
              f"{info['bitrate_kbps']} kbps | {info['sample_rate']} Hz | "
              f"{info['channels']} | 约 {dur}")
        if info["marks"]:
            print(f"   编码器标记: {', '.join(info['marks'])}")
        print(f"   {info['verdict']}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
