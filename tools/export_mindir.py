# -*- coding: utf-8 -*-
"""
export_mindir.py —— 把 finetuned.ckpt 导出成 MindIR（模型选型 4.1 的"部署"第一步）

为什么先导 MindIR：
    MindSpore 训练出来的权重是 .ckpt（只含参数，不含图）。手机端要跑的是"图 + 参数"，
    所以必须先固定输入形状、把整张计算图定格下来。MindIR 就是这个定格结果，
    它再往下可以转成 MindSpore Lite 的 .ms，或者在部分 Lite 版本上直接加载。

用法：
    PYTHONPATH= D:/models/.conda-ms/python.exe tools/export_mindir.py
    PYTHONPATH= D:/models/.conda-ms/python.exe tools/export_mindir.py --len 64
"""

import argparse
import json
import os
import sys

import numpy as np

import mindspore as ms
from mindspore import Tensor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train_tinybert_ms import MODEL_DIR, TinyBertClassifier  # noqa: E402

CFG_PATH = os.path.join(MODEL_DIR, "config.json")
CKPT_PATH = os.path.join(MODEL_DIR, "finetuned.ckpt")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--len", type=int, default=64, help="固定序列长度（训练时就是 64）")
    ap.add_argument("--out", default=os.path.join(MODEL_DIR, "tinybert_cls"))
    a = ap.parse_args()

    if not os.path.exists(CKPT_PATH):
        raise SystemExit(f"找不到权重：{CKPT_PATH}，先跑 train_tinybert_ms.py")

    ms.set_context(mode=ms.GRAPH_MODE, device_target="CPU")

    with open(CFG_PATH, encoding="utf-8") as f:
        cfg = json.load(f)

    net = TinyBertClassifier(cfg)
    params = ms.load_checkpoint(CKPT_PATH, net=net)
    print(f"[权重] 已载入 {len(params)} 个参数")
    net.set_train(False)          # 关掉 dropout，图里不留随机算子

    # 输入形状必须固定：手机端每次都按这个长度补齐/截断
    ids = Tensor(np.zeros((1, a.len), np.int32))
    add_mask = Tensor(np.zeros((1, 1, 1, a.len), np.float32))

    ms.export(net, ids, add_mask, file_name=a.out, file_format="MINDIR")

    p = a.out + ".mindir"
    size = os.path.getsize(p) / 1024 / 1024
    print(f"[导出] {p}  {size:.1f} MB")
    print(f"[说明] 输入 0: ids int32 (1,{a.len})｜输入 1: add_mask float32 (1,1,1,{a.len})")
    print("       输出: logits float32 (1,2)，softmax 后取第 1 位就是诈骗概率")


if __name__ == "__main__":
    main()
