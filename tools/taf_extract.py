#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 TeleAntiFraud 的 audio.zip 按类别等比抽样，解出 mp3 + 生成 index.jsonl。

标签用 zip 内的目录名（POS-* = 诈骗，NEG-* = 正常），
不用 sft.zip 里的 is_fraud——那份打包出来 true 7177 / false 0，不可用。

产物：
    <OUT>/*.mp3              文件名 <group>__<子目录>.mp3
    <OUT>/index.jsonl        每行 {"file","label","group"}
"""
import collections
import json
import os
import random
import zipfile

SRC = "D:/下载/audio.zip"
OUT = "D:/华为ict/外部数据集/taf_audio"
TARGET_POS = 3600   # 诈骗侧（后续 ASR 会因过短丢一部分，故多抽）
TARGET_NEG = 3600   # 正常侧
SEED = 7

os.makedirs(OUT, exist_ok=True)
rnd = random.Random(SEED)
z = zipfile.ZipFile(SRC)
by = collections.defaultdict(list)
for i in z.infolist():
    if i.filename.endswith(".mp3"):
        by[i.filename.split("/")[1]].append(i)

pos_groups = sorted(k for k in by if k.startswith("POS"))
neg_groups = sorted(k for k in by if k.startswith("NEG"))


def quota(groups, total):
    n_all = sum(len(by[g]) for g in groups)
    q = {}
    for g in groups:
        q[g] = max(1, round(len(by[g]) / n_all * total))
    return q


plan = {**quota(pos_groups, TARGET_POS), **quota(neg_groups, TARGET_NEG)}
idx, done = [], 0
for g in sorted(plan):
    items = rnd.sample(by[g], min(plan[g], len(by[g])))
    label = "fraud" if g.startswith("POS") else "normal"
    for it in items:
        sub = it.filename.split("/")[2]
        name = f"{g}__{sub}.mp3"
        dst = os.path.join(OUT, name)
        if not os.path.exists(dst):
            with open(dst, "wb") as f:
                f.write(z.read(it))
        idx.append({"file": name, "label": label, "group": g})
        done += 1
    print(f"{g:22s} 取 {len(items):5d}  ({label})", flush=True)

with open(os.path.join(OUT, "index.jsonl"), "w", encoding="utf-8") as f:
    for d in idx:
        f.write(json.dumps(d, ensure_ascii=False) + "\n")

print("总文件", len(idx), "诈骗", sum(1 for d in idx if d["label"] == "fraud"),
      "正常", sum(1 for d in idx if d["label"] == "normal"))
