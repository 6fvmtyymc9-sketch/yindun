#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
真人录音收料工具（老师六步计划 第 1 步）

用法：
    PYTHONPATH= /d/python/python.exe tools/intake_rec.py <wav> [<wav> ...]

做三件事：
  1. 量参数：时长 / 采样率 / 有效语音占比 / 削顶率 / 内部停顿（判是不是双人对答）
  2. 归档：复制到 外部数据集/录音_20261121/raw/takeNN_待定_XXs.wav（原名登记在 MANIFEST.csv）
  3. 打印一行结论：达标 / 偏短 / 有问题

不改原始文件，不碰 16k 重采样（那步在服务端 AudioResample.js）。
"""
import os
import sys
import csv
import wave
import shutil

import numpy as np

RAW_DIR = os.path.join('D:/华为ict/外部数据集/录音_20261121', 'raw')
CSV_PATH = os.path.join('D:/华为ict/外部数据集/录音_20261121', 'MANIFEST.csv')

MIN_SEC, MAX_SEC = 20.0, 90.0
CLIP_LEVEL = 32700
SILENCE_ABS = 500


def measure(path):
    w = wave.open(path, 'rb')
    try:
        fr, ch, sw, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        raw = w.readframes(n)
    finally:
        w.close()

    if sw != 2:
        a = np.frombuffer(raw, dtype='<i2').astype(np.float32) if sw == 2 else None
        if a is None:
            raise ValueError('只支持 16bit PCM，当前位深 %d' % (sw * 8))
    a = np.frombuffer(raw, dtype='<i2').astype(np.float32)
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)

    dur = len(a) / fr
    clip = float((np.abs(a) >= CLIP_LEVEL).mean() * 100)
    speech = float((np.abs(a) > SILENCE_ABS).mean() * 100)

    # 停顿：20ms 帧，阈值取绝对 300 与 15 分位的 2 倍中较大者
    hop = max(1, int(fr * 0.02))
    nf = len(a) // hop
    e = np.sqrt((a[:nf * hop].reshape(nf, hop) ** 2).mean(axis=1))
    thr = max(300.0, float(np.percentile(e, 15)) * 2)
    sil = e < thr
    pauses, i = [], 0
    while i < nf:
        if sil[i]:
            j = i
            while j < nf and sil[j]:
                j += 1
            sec = (j - i) * 0.02
            if sec >= 0.35 and i > 0 and j < nf:
                pauses.append(round(sec, 2))
            i = j
        else:
            i += 1

    return {
        'duration_sec': round(dur, 2),
        'sample_rate': fr,
        'channels': ch,
        'bits': sw * 8,
        'speech_pct': round(speech, 1),
        'clip_pct': round(clip, 2),
        'pauses': len(pauses),
        'pause_max': max(pauses) if pauses else 0.0,
    }


def verdict(m):
    if m['bits'] != 16 or m['channels'] != 1:
        return '改格式', '需要 16bit 单声道'
    if m['duration_sec'] < MIN_SEC:
        return '偏短', '有效语音太短，不进评估集，留作链路冒烟'
    if m['duration_sec'] > MAX_SEC:
        return '偏长', '超 90 秒，可剪成两通或重录'
    if m['pauses'] < 2:
        return '存疑', '几乎没有停顿，可能是单口念稿，需人耳复核'
    if m['clip_pct'] > 2.0:
        return '削顶高', '削顶 >2%%，建议降音量重录'
    return '达标', '可进评估集'


def next_index():
    if not os.path.isdir(RAW_DIR):
        os.makedirs(RAW_DIR, exist_ok=True)
    nums = []
    for f in os.listdir(RAW_DIR):
        if f.startswith('take') and f[4:6].isdigit():
            nums.append(int(f[4:6]))
    return (max(nums) + 1) if nums else 1


def main(argv):
    if not argv:
        print(__doc__)
        return 1

    idx = next_index()
    rows = []
    for src in argv:
        if not os.path.isfile(src):
            print('跳过（不存在）：%s' % src)
            continue
        try:
            m = measure(src)
        except Exception as e:
            print('跳过（解析失败）：%s -> %s' % (src, e))
            continue

        tag, why = verdict(m)
        dur = int(round(m['duration_sec']))
        dst = os.path.join(RAW_DIR, 'take%02d_待定_%ds.wav' % (idx, dur))
        shutil.copy2(src, dst)
        os.chmod(dst, 0o644)

        print('[%02d] %-16s %5.2fs  %dHz  %s  有效%.1f%%  削顶%.2f%%  停顿%d处(最长%.1fs)  -> %s | %s'
              % (idx, os.path.basename(src), m['duration_sec'], m['sample_rate'],
                 '单声道', m['speech_pct'], m['clip_pct'], m['pauses'], m['pause_max'], tag, why))

        rows.append([idx, os.path.basename(src), os.path.basename(dst),
                     m['duration_sec'], m['sample_rate'], m['speech_pct'],
                     m['clip_pct'], m['pauses'], tag, ''])
        idx += 1

    if rows:
        new = not os.path.exists(CSV_PATH)
        with open(CSV_PATH, 'a', newline='', encoding='utf-8-sig') as f:
            wr = csv.writer(f)
            if new:
                wr.writerow(['序号', '原始文件', '归档文件', '时长s', '采样率',
                             '有效语音%', '削顶%', '停顿数', '判定', '场景(待填)'])
            wr.writerows(rows)
        print('\n登记：%s' % CSV_PATH)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
