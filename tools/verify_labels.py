#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
评估集标签核对（老师六步计划 第 1 步配套）

为什么要有它：2026-11-21 出了一次恶性错标——按"文件序号=脚本场次"推场景，
结果 10 条录音的标签全反了（诈骗标成正常、正常标成诈骗）。标签错则召回/误报全反，
而且表面上看还"跑通了"，比跑不通危险得多。

做法：把每条音频送线上 /api/callaudio（dryRun，不写库不推送），
拿判定类型与理由去比对 labels.json 的 label/scenario，对不上就标红。

用法：
    PYTHONPATH= /d/python/python.exe tools/verify_labels.py
    PYTHONPATH= /d/python/python.exe tools/verify_labels.py --dir <音频目录> [--base URL]

成本：每条约 ¥0.0013（SIS 转写 + DeepSeek 判定）。
"""
import argparse
import base64
import glob
import json
import os
import sys
import urllib.error
import urllib.request
import wave

DEFAULT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           'cloud', 'test', 'calls_eval')
DEFAULT_BASE = 'http://123.249.25.139:8080'


def duration(path):
    w = wave.open(path, 'rb')
    try:
        return w.getnframes() / float(w.getframerate())
    finally:
        w.close()


def probe(base, path, dur, idx):
    body = json.dumps({
        'elderId': 'demo',
        'number': '1380009%04d' % idx,
        'name': os.path.basename(path),   # 必须带后缀，否则服务端按 mp3 处理，长录音切不了段
        'durSec': dur,
        'audio': base64.b64encode(open(path, 'rb').read()).decode(),
        'dryRun': True,
    }).encode()
    req = urllib.request.Request(base + '/api/callaudio', data=body,
                                 headers={'Content-Type': 'application/json'})
    try:
        return json.loads(urllib.request.urlopen(req, timeout=300).read()), None
    except urllib.error.HTTPError as e:
        return None, 'HTTP %d %s' % (e.code, e.read().decode('utf-8', 'ignore')[:120])
    except Exception as e:
        return None, str(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', default=DEFAULT_DIR)
    ap.add_argument('--base', default=DEFAULT_BASE)
    a = ap.parse_args()

    labels_path = os.path.join(a.dir, 'labels.json')
    meta = json.load(open(labels_path, encoding='utf-8')) if os.path.exists(labels_path) else {}
    by_file = {os.path.basename(i['file']): i for i in meta.get('items', [])}

    files = sorted(glob.glob(os.path.join(a.dir, 'audio', '*.wav')) +
                   glob.glob(os.path.join(a.dir, 'audio', '*.mp3')))
    if not files:
        print('没有音频：%s/audio' % a.dir)
        return 1

    print('核对 %d 条  base=%s\n' % (len(files), a.base))
    bad, fail = [], []
    for i, f in enumerate(files, 1):
        name = os.path.basename(f)
        dur = duration(f)
        j, err = probe(a.base, f, dur, i)
        if err:
            print('%-34s %5.1fs  ✗ 失败 %s' % (name, dur, err))
            fail.append(name)
            continue
        typ = j.get('type') or '-'
        lvl = j.get('level')
        reason = (j.get('reason') or '')[:46]
        exp = by_file.get(name)
        if not exp:
            print('%-34s %5.1fs  %-6s %-14s %s   ← 不在 labels.json 里' % (name, dur, lvl, typ, reason))
            bad.append(name)
            continue
        # 一致性判据：诈骗样本应判 isFraud，正常样本不应判 isFraud
        is_fraud = bool(j.get('isFraud'))
        ok = (is_fraud == (exp['label'] == '诈骗'))
        print('%-34s %5.1fs  %-6s %-14s %s  %s' %
              (name, dur, lvl, typ, reason, '✓' if ok else '✗ 标签与内容矛盾'))
        if not ok:
            bad.append(name)

    print('\n一致 %d / 矛盾 %d / 失败 %d' % (len(files) - len(bad) - len(fail), len(bad), len(fail)))
    if bad:
        print('矛盾文件：')
        for n in bad:
            print('  -', n)
        print('→ 按上面打印的实际内容改名并更新 labels.json，不要按序号推。')
    return 0 if not bad and not fail else 2


if __name__ == '__main__':
    sys.exit(main())
