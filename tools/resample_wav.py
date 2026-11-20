#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把任意 WAV 重采样成 8k 单声道 16bit —— 也就是真实电话的带宽。

为什么需要它：
  1. 服务端 SIS 一句话识别单次吃不下大文件（实测 4.6MB 的 44.1k WAV 直接
     报 "data element too large"，而 8k/45 秒 703KB 能过）；
  2. 8k 单声道正是电话信道的真实参数，和我们评估集、线上手机录音一致，
     降采样不会引入"分布不一致"的问题。

不依赖 ffmpeg / numpy：本机两样都没有，纯 wave + 标准库做。
抗混叠用简单滑动平均（对语音 ASR 够用；这是工具不是音频出版）。

用法：
  python tools/resample_wav.py <输入目录或文件> [--out 输出目录] [--rate 8000] [--seg 55]
  --seg 给了就按秒切段（切段在重采样之后做，保证每段体积可控）。
"""
import argparse
import array
import os
import sys
import wave


def read_wav(path):
    w = wave.open(path, 'rb')
    ch = w.getnchannels()
    sr = w.getframerate()
    sw = w.getsampwidth()
    n = w.getnframes()
    raw = w.readframes(n)
    w.close()
    return ch, sr, sw, raw


def to_mono_16(raw, ch, sw):
    """统一成单声道 16bit 的 sample 序列（int）。"""
    if sw == 1:                                  # 8bit 无符号
        samples = [(b - 128) << 8 for b in raw]
    elif sw == 2:                                # 16bit
        a = array.array('h')
        a.frombytes(raw[:len(raw) - len(raw) % 2])
        if sys.byteorder == 'big':
            a.byteswap()
        samples = list(a)
    elif sw == 3:                                # 24bit
        samples = []
        for i in range(0, len(raw) - 2, 3):
            v = raw[i] | (raw[i + 1] << 8) | (raw[i + 2] << 16)
            if v & 0x800000:
                v -= 1 << 24
            samples.append(v >> 8)
    elif sw == 4:                                # 32bit
        a = array.array('i')
        a.frombytes(raw[:len(raw) - len(raw) % 4])
        if sys.byteorder == 'big':
            a.byteswap()
        samples = [v >> 16 for v in a]
    else:
        raise ValueError('不支持的位深 %d' % (sw * 8))

    if ch > 1:
        out = []
        for i in range(0, len(samples) - ch + 1, ch):
            out.append(sum(samples[i:i + ch]) // ch)
        return out
    return samples


def lowpass(samples, win):
    """滑动平均低通，先滤掉高频再抽取，避免混叠成刺耳的噪声。"""
    if win <= 1:
        return samples
    out = []
    acc = 0
    q = [0] * win
    for i, s in enumerate(samples):
        acc += s - q[i % win]
        q[i % win] = s
        # 前 win 个点用已累积的平均，避免开头音量被压低
        k = win if i >= win else i + 1
        out.append(acc // k)
    return out


def resample(samples, src_rate, dst_rate):
    if src_rate == dst_rate:
        return samples
    ratio = dst_rate / float(src_rate)
    if ratio < 1:
        samples = lowpass(samples, max(1, int(round(1 / ratio))))
    n_out = int(len(samples) * ratio)
    out = []
    for i in range(n_out):
        pos = i / ratio
        i0 = int(pos)
        i1 = min(i0 + 1, len(samples) - 1)
        frac = pos - i0
        out.append(int(samples[i0] * (1 - frac) + samples[i1] * frac))
    return out


def write_wav(path, samples, rate):
    a = array.array('h', samples)
    if sys.byteorder == 'big':
        a.byteswap()
    w = wave.open(path, 'wb')
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(rate)
    w.writeframes(a.tobytes())
    w.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src', help='输入文件或目录')
    ap.add_argument('--out', default='', help='输出目录，默认与输入同级的 out_8k/')
    ap.add_argument('--rate', type=int, default=8000)
    ap.add_argument('--seg', type=float, default=0, help='按秒切段，0=不切')
    args = ap.parse_args()

    if os.path.isdir(args.src):
        files = sorted(os.path.join(args.src, f) for f in os.listdir(args.src)
                       if f.lower().endswith('.wav'))
    else:
        files = [args.src]
    if not files:
        print('没找到 wav 文件')
        return 1

    out_dir = args.out or os.path.join(
        os.path.dirname(os.path.abspath(files[0])), 'out_%dk' % (args.rate // 1000))
    os.makedirs(out_dir, exist_ok=True)

    for p in files:
        ch, sr, sw, raw = read_wav(p)
        samples = to_mono_16(raw, ch, sw)
        samples = resample(samples, sr, args.rate)
        base = os.path.splitext(os.path.basename(p))[0]
        if args.seg and args.seg > 0:
            step = int(args.seg * args.rate)
            parts = [samples[i:i + step] for i in range(0, len(samples), step)]
            for i, part in enumerate(parts, 1):
                q = os.path.join(out_dir, '%s_%02d.wav' % (base, i))
                write_wav(q, part, args.rate)
                print('  %-28s %5.1f 秒  %6.0f KB'
                      % (os.path.basename(q), len(part) / args.rate,
                         os.path.getsize(q) / 1024))
        else:
            q = os.path.join(out_dir, base + '.wav')
            write_wav(q, samples, args.rate)
            print('  %-28s %5.1f 秒  %6.0f KB'
                  % (os.path.basename(q), len(samples) / args.rate,
                     os.path.getsize(q) / 1024))
        print('    （原 %d Hz %d 声道 → %d Hz 单声道）' % (sr, ch, args.rate))
    print('\n输出目录：' + out_dir)
    return 0


if __name__ == '__main__':
    sys.exit(main())
