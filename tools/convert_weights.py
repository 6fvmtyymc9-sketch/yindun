#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 TinyBERT_4L_zh 的 PyTorch 权重跨到 NumPy
====================================================================
为什么要有这一步：
    训练要用 MindSpore（模型选型 4.1 要求，华为技术栈），但 MagicMind 里没有
    PyTorch 的加载器；MindSpore 也不能直接读 pytorch_model.bin。
    所以中间过一手 .npz —— 两边都能读，且是个可检查的中间产物。

    本脚本跑在**装了 torch 的环境**（D:\\models\\.venv-sft），
    训练脚本跑在**装了 mindspore 的环境**（D:\\models\\.conda-ms）。

只搬 bert.* 前缀的参数：
    cls.predictions.* 和 fit_dense.* 是 TinyBERT 蒸馏时用的，做分类用不上。

用法：
    PYTHONPATH= D:/models/.venv-sft/Scripts/python.exe tools/convert_weights.py
"""

import os

import numpy as np
import torch

SRC = os.environ.get("TINYBERT_DIR", r"D:\models\tinybert-4l-zh")
IN_BIN = os.path.join(SRC, "pytorch_model.bin")
OUT_NPZ = os.path.join(SRC, "weights.npz")

KEEP = "bert."


def main():
    sd = torch.load(IN_BIN, map_location="cpu", weights_only=True)
    kept = {k: v.float().numpy() for k, v in sd.items() if k.startswith(KEEP)}
    dropped = [k for k in sd if not k.startswith(KEEP)]
    np.savez_compressed(OUT_NPZ, **kept)

    print(f"来源 {IN_BIN}")
    print(f"总参数 {len(sd)}，保留 {len(kept)}（前缀 {KEEP}），丢弃 {len(dropped)}")
    print(f"丢弃的：{dropped}")
    print(f"输出 {OUT_NPZ}  {os.path.getsize(OUT_NPZ) / 1e6:.1f} MB")
    for k in list(kept)[:3]:
        print(f"  {k} {kept[k].shape}")


if __name__ == "__main__":
    main()
