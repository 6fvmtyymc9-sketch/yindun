#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TinyBERT 端侧推理耗时
====================================================================
对比表里"耗时"这一列不能只有云端和规则的数。这里测的是**单条短信在本机 CPU
上一次前向**的实际耗时 —— 端侧部署时最关心的那个数。

要区分两个数：
    warm  预热之后每次前向的时间   ← 这才是端侧真实体验
    first 第一次前向               ← 含图编译/算子准备，别拿它当道理

用法：
    PYTHONPATH= D:/models/.conda-ms/python.exe tools/bench_tinybert.py
"""

import json
import os
import sys
import time

import mindspore as ms
from mindspore import Tensor, ops

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train_tinybert_ms as T   # noqa: E402  复用同一套网络定义和分词

MODEL_DIR = T.MODEL_DIR


def main():
    ms.set_context(mode=ms.GRAPH_MODE, device_target="CPU")
    with open(os.path.join(MODEL_DIR, "config.json"), encoding="utf-8") as f:
        cfg = json.load(f)
    cfg.setdefault("layer_norm_eps", 1e-12)

    net = T.TinyBertClassifier(cfg)
    ckpt = os.path.join(MODEL_DIR, "finetuned.ckpt")
    ms.load_checkpoint(ckpt, net)
    net.set_train(False)
    tok = T.CharTokenizer(os.path.join(MODEL_DIR, "vocab.txt"), 64)

    text = "这里是派出所，你的银行账户被用于电信诈骗，现在要冻结，配合我们做资金核查"
    ids, mask = tok.encode(text)
    add_mask = ((1.0 - __import__("numpy").array([mask], dtype="float32")) * -1e9)[:, None, None, :]

    x = Tensor(__import__("numpy").array([ids], dtype="int32"))
    m = Tensor(add_mask.astype("float32"))

    t0 = time.time()
    out = net(x, m)
    first = (time.time() - t0) * 1000
    p = float(ops.softmax(out, axis=-1).asnumpy()[0, 1])

    n = 50
    t1 = time.time()
    for _ in range(n):
        net(x, m)
    warm = (time.time() - t1) / n * 1000

    print(f"权重 {ckpt}  {os.path.getsize(ckpt) / 1e6:.1f} MB")
    print(f"样例判定 p(诈骗)={p:.3f}  文本 {len(text)} 字")
    print(f"首次前向 {first:.1f} ms（含图编译，不算数）")
    print(f"预热后   {warm:.2f} ms/条  ← 端侧实际体验")


if __name__ == "__main__":
    main()
