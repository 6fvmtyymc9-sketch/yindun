#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端侧中文文本小模型 —— TinyBERT_4L_zh 微调 + 留出集评估
====================================================================
按《模型选型说明》4.1 节做：
    基座  huawei-noah/TinyBERT_4L_zh（华为出品，4 层，312 维，端侧可跑）
    框架  MindSpore 2.11（华为技术栈）
    评估  **只用 cloud/test/holdout.json**，训练绝不碰它
    对比  和规则 v3.4 的数字并排（漏报 60% / 误报 0%）

为什么要提"训练绝不用留出集"：
    留出集的意义就是"没见过的样本"。拿它调过模型，报出来的数字就是假的。
    所以本脚本里，训练只读 cases.json，holdout 只在这里被读一次、只用于打分。

为什么自己搭 BERT 而不用现成的 NLP 库：
    MindSpore 读不了 PyTorch 的 .bin。中间过一手 .npz（见 convert_weights.py），
    再按 BERT 的标准结构把 71 个参数填进来 —— 结构简单到没必要引一整个库，
    而且答辩时能说清"每一层是我们自己搭的"。

用法：
    PYTHONPATH= D:/models/.conda-ms/python.exe tools/train_tinybert_ms.py
    PYTHONPATH= D:/models/.conda-ms/python.exe tools/train_tinybert_ms.py --epochs 15
"""

import argparse
import json
import math
import os
import random
import time

import numpy as np

import mindspore as ms
from mindspore import Tensor, nn, ops

MODEL_DIR = os.environ.get("TINYBERT_DIR", r"D:\models\tinybert-4l-zh")
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TRAIN_JSON = os.path.join(REPO, "cloud", "test", "cases.json")
HOLDOUT_JSON = os.path.join(REPO, "cloud", "test", "holdout.json")
SYN_JSON = os.path.join(REPO, "cloud", "test", "synthetic.json")
# ChiFraud 不下进仓库（95MB），放本地临时目录，用 --chi-csv 指过去
CHI_CSV = os.path.join(os.path.dirname(REPO), ".tmp-verify", "data", "ChiFraud_train.csv")
OUT_JSON = os.path.join(REPO, "cloud", "test", "out-tinybert.json")


# ---------------------------------------------------------------- 分词
class CharTokenizer:
    """
    中文短信用字级切分就够：BERT 的中文词表本来就是按字建的。
    ASCII 的连续字母/数字合成一个 token（"10086"、"ETC" 不该被拆散），
    查不到就退回 [UNK]。
    """

    def __init__(self, vocab_path, max_len=64):
        self.vocab = {}
        with open(vocab_path, encoding="utf-8") as f:
            for i, line in enumerate(f):
                self.vocab[line.rstrip("\n")] = i
        self.max_len = max_len
        self.cls = self.vocab.get("[CLS]", 101)
        self.sep = self.vocab.get("[SEP]", 102)
        self.unk = self.vocab.get("[UNK]", 100)
        self.pad = self.vocab.get("[PAD]", 0)

    def _cut(self, text):
        out, buf = [], ""
        for ch in text:
            if ch.isascii() and ch.isalnum():
                buf += ch
                continue
            if buf:
                out.append(buf)
                buf = ""
            out.append(ch)
        if buf:
            out.append(buf)
        return out

    def encode(self, text):
        toks = self._cut(str(text))[: self.max_len - 2]
        ids = [self.cls] + [self.vocab.get(t, self.unk) for t in toks] + [self.sep]
        mask = [1] * len(ids)
        while len(ids) < self.max_len:
            ids.append(self.pad)
            mask.append(0)
        return ids, mask


# ---------------------------------------------------------------- 网络
class BertLayer(nn.Cell):
    def __init__(self, cfg):
        super().__init__()
        h = cfg["hidden_size"]
        self.heads = cfg["num_attention_heads"]
        self.head_dim = h // self.heads
        self.scale = 1.0 / math.sqrt(self.head_dim)

        self.query = nn.Dense(h, h)
        self.key = nn.Dense(h, h)
        self.value = nn.Dense(h, h)
        self.attn_out = nn.Dense(h, h)
        self.attn_norm = nn.LayerNorm((h,), epsilon=cfg["layer_norm_eps"])

        self.inter = nn.Dense(h, cfg["intermediate_size"])
        self.out = nn.Dense(cfg["intermediate_size"], h)
        self.out_norm = nn.LayerNorm((h,), epsilon=cfg["layer_norm_eps"])

        self.gelu = nn.GELU()
        self.softmax = ops.Softmax(axis=-1)

    def construct(self, x, add_mask):
        b, s, h = x.shape
        shape = (b, s, self.heads, self.head_dim)

        q = self.query(x).reshape(shape).transpose(0, 2, 1, 3)
        k = self.key(x).reshape(shape).transpose(0, 2, 1, 3)
        v = self.value(x).reshape(shape).transpose(0, 2, 1, 3)

        scores = ops.matmul(q, k.transpose(0, 1, 3, 2)) * self.scale
        scores = scores + add_mask                       # 被遮的位置加负无穷
        ctx = ops.matmul(self.softmax(scores), v)
        ctx = ctx.transpose(0, 2, 1, 3).reshape((b, s, h))

        x = self.attn_norm(self.attn_out(ctx) + x)       # 残差 + LayerNorm
        x = self.out_norm(self.out(self.gelu(self.inter(x))) + x)
        return x


class TinyBertClassifier(nn.Cell):
    def __init__(self, cfg, num_labels=2, dropout=0.1):
        super().__init__()
        h = cfg["hidden_size"]
        self.word = nn.Embedding(cfg["vocab_size"], h)
        self.pos = nn.Embedding(cfg["max_position_embeddings"], h)
        self.type = nn.Embedding(cfg["type_vocab_size"], h)
        self.emb_norm = nn.LayerNorm((h,), epsilon=cfg["layer_norm_eps"])

        self.layers = nn.CellList([BertLayer(cfg) for _ in range(cfg["num_hidden_layers"])])
        self.pooler = nn.Dense(h, h)
        self.dropout = nn.Dropout(p=dropout)
        self.classifier = nn.Dense(h, num_labels)
        self.tanh = nn.Tanh()
        self.expand = ops.ExpandDims()

    def construct(self, ids, add_mask):
        b, s = ids.shape
        pos_ids = ops.broadcast_to(self.expand(ops.arange(s, dtype=ms.int32), 0), (b, s))
        x = self.word(ids) + self.pos(pos_ids) + self.type(ops.zeros_like(ids))
        x = self.emb_norm(self.dropout(x))
        for layer in self.layers:
            x = layer(x, add_mask)
        pooled = self.tanh(self.pooler(x[:, 0]))          # 取 [CLS] 位置
        return self.classifier(self.dropout(pooled))


def load_weights(net, npz_path):
    """把 npz 里的 71 个参数按名字填进网络。填不上就直接报错，不静默跳过。"""
    w = np.load(npz_path)
    miss = []

    def put(param, key):
        if key not in w:
            miss.append(key)
            return
        arr = w[key]
        if tuple(param.shape) != tuple(arr.shape):
            raise ValueError(f"{key} 形状不符：网络 {param.shape} vs 权重 {arr.shape}")
        param.set_data(Tensor(arr, ms.float32))

    put(net.word.embedding_table, "bert.embeddings.word_embeddings.weight")
    put(net.pos.embedding_table, "bert.embeddings.position_embeddings.weight")
    put(net.type.embedding_table, "bert.embeddings.token_type_embeddings.weight")
    put(net.emb_norm.gamma, "bert.embeddings.LayerNorm.weight")
    put(net.emb_norm.beta, "bert.embeddings.LayerNorm.bias")

    for i, layer in enumerate(net.layers):
        p = f"bert.encoder.layer.{i}."
        put(layer.query.weight, p + "attention.self.query.weight")
        put(layer.query.bias, p + "attention.self.query.bias")
        put(layer.key.weight, p + "attention.self.key.weight")
        put(layer.key.bias, p + "attention.self.key.bias")
        put(layer.value.weight, p + "attention.self.value.weight")
        put(layer.value.bias, p + "attention.self.value.bias")
        put(layer.attn_out.weight, p + "attention.output.dense.weight")
        put(layer.attn_out.bias, p + "attention.output.dense.bias")
        put(layer.attn_norm.gamma, p + "attention.output.LayerNorm.weight")
        put(layer.attn_norm.beta, p + "attention.output.LayerNorm.bias")
        put(layer.inter.weight, p + "intermediate.dense.weight")
        put(layer.inter.bias, p + "intermediate.dense.bias")
        put(layer.out.weight, p + "output.dense.weight")
        put(layer.out.bias, p + "output.dense.bias")
        put(layer.out_norm.gamma, p + "output.LayerNorm.weight")
        put(layer.out_norm.beta, p + "output.LayerNorm.bias")

    put(net.pooler.weight, "bert.pooler.dense.weight")
    put(net.pooler.bias, "bert.pooler.dense.bias")

    if miss:
        raise KeyError(f"这些权重在 npz 里找不到：{miss}")
    return len(w.files)


# ---------------------------------------------------------------- 数据
def load_set(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)["cases"]


def load_chi_negatives(csv_path, n, seed=42):
    """从 ChiFraud 抽 Label_id=0 的正常文本当负样本。

    注意：ChiFraud 的正常样本是网页新闻、微博、装修广告，跟老人手机里的短信
    不是同一个分布，所以只当"难负样本"少量掺入，并在报告里标明来源。
    """
    rows = []
    with open(csv_path, encoding="utf-8", errors="replace") as f:
        next(f, None)
        for line in f:
            p = line.rstrip("\n").split("\t", 1)
            if len(p) != 2 or p[0].strip() != "0":
                continue
            t = p[1].strip()
            if 15 <= len(t) <= 120:
                rows.append(t)
    random.Random(seed).shuffle(rows)
    picked = rows[:n]
    return [{"text": t, "expect": "normal", "type": "chifraud-neg", "synthetic": False} for t in picked]


def make_batch(tokenizer, cases, idxs, max_len):
    ids, masks, labels = [], [], []
    for i in idxs:
        a, m = tokenizer.encode(cases[i]["text"])
        ids.append(a)
        masks.append(m)
        labels.append(1 if cases[i]["expect"] == "fraud" else 0)
    ids = np.array(ids, dtype=np.int32)
    masks = np.array(masks, dtype=np.float32)
    add_mask = (1.0 - masks) * -1e9
    add_mask = add_mask[:, None, None, :].astype(np.float32)
    return Tensor(ids), Tensor(add_mask), Tensor(np.array(labels, dtype=np.int32))


def predict(net, tokenizer, cases, max_len, batch=16):
    probs = []
    for start in range(0, len(cases), batch):
        idxs = list(range(start, min(start + batch, len(cases))))
        ids, mask, _ = make_batch(tokenizer, cases, idxs, max_len)
        logits = net(ids, mask)
        p = ops.softmax(logits, axis=-1).asnumpy()[:, 1]
        probs.extend(p.tolist())
    return probs


# ---------------------------------------------------------------- 主流程
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--lr", type=float, default=3e-5)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--max-len", type=int, default=64)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--graph", action="store_true", help="图模式，CPU 上快得多")
    ap.add_argument("--synthetic", default=SYN_JSON, help="合成样本（大模型换说法扩写），给空串则不使用")
    ap.add_argument("--chi-neg", type=int, default=0, help="额外掺入 ChiFraud 正常文本的条数")
    ap.add_argument("--chi-csv", default=CHI_CSV, help="ChiFraud 训练集 CSV 路径")
    ap.add_argument("--tag", default="", help="结果文件名后缀，便于区分不同配置")
    a = ap.parse_args()

    global OUT_JSON
    if a.tag:
        OUT_JSON = OUT_JSON.replace(".json", f"-{a.tag}.json")

    random.seed(a.seed)
    np.random.seed(a.seed)
    ms.set_seed(a.seed)
    ms.set_context(mode=ms.GRAPH_MODE if a.graph else ms.PYNATIVE_MODE, device_target="CPU")

    with open(os.path.join(MODEL_DIR, "config.json"), encoding="utf-8") as f:
        cfg = json.load(f)
    cfg.setdefault("layer_norm_eps", 1e-12)
    print(f"[配置] {cfg['num_hidden_layers']} 层 / {cfg['hidden_size']} 维 / "
          f"{cfg['num_attention_heads']} 头 / 词表 {cfg['vocab_size']}")

    tok = CharTokenizer(os.path.join(MODEL_DIR, "vocab.txt"), a.max_len)
    train = load_set(TRAIN_JSON)
    if a.synthetic and os.path.exists(a.synthetic):
        syn = load_set(a.synthetic)
        train = train + syn
        print(f"[数据] 并入合成样本 {len(syn)} 条（诈骗 {sum(1 for c in syn if c['expect']=='fraud')}）")
    if a.chi_neg > 0:
        if not os.path.exists(a.chi_csv):
            raise SystemExit(f"找不到 ChiFraud 文件：{a.chi_csv}")
        neg = load_chi_negatives(a.chi_csv, a.chi_neg, a.seed)
        train = train + neg
        print(f"[数据] 并入 ChiFraud 正常文本 {len(neg)} 条")
    random.Random(a.seed).shuffle(train)
    holdout = load_set(HOLDOUT_JSON)
    print(f"[数据] 训练 {len(train)} 条（诈骗 {sum(1 for c in train if c['expect']=='fraud')}）"
          f"｜留出 {len(holdout)} 条（诈骗 {sum(1 for c in holdout if c['expect']=='fraud')}）")

    net = TinyBertClassifier(cfg)
    n = load_weights(net, os.path.join(MODEL_DIR, "weights.npz"))
    print(f"[权重] 已加载 {n} 个参数")

    loss_fn = nn.CrossEntropyLoss()
    opt = nn.Adam(net.trainable_params(), learning_rate=a.lr)


    def forward_fn(ids, mask, label):
        return loss_fn(net(ids, mask), label)


    grad_fn = ms.value_and_grad(forward_fn, None, opt.parameters)

    t0 = time.time()
    for ep in range(a.epochs):
        order = list(range(len(train)))
        random.shuffle(order)
        total, steps = 0.0, 0
        net.set_train(True)
        for start in range(0, len(order), a.batch):
            idxs = order[start:start + a.batch]
            ids, mask, label = make_batch(tok, train, idxs, a.max_len)
            loss, grads = grad_fn(ids, mask, label)
            opt(grads)
            total += float(loss)
            steps += 1
        print(f"  epoch {ep + 1:2}/{a.epochs}  loss {total / steps:.4f}  ({int(time.time() - t0)}s)")

    net.set_train(False)
    print(f"[训练] 完成，{int(time.time() - t0)} 秒")

    # ---- 留出集评估（只在这里读一次留出集）
    probs = predict(net, tok, holdout, a.max_len)
    rows = []
    for c, p in zip(holdout, probs):
        truth = c["expect"] == "fraud"
        pred = p >= 0.5
        rows.append({
            "type": c.get("type", ""),
            "text": c["text"],
            "expect": c["expect"],
            "p": round(float(p), 4),
            "pred": "fraud" if pred else "normal",
            "hit": pred == truth,
        })

    fraud = [r for r in rows if r["expect"] == "fraud"]
    normal = [r for r in rows if r["expect"] != "fraud"]
    miss = [r for r in fraud if r["pred"] != "fraud"]
    falarm = [r for r in normal if r["pred"] == "fraud"]

    print("\n—— 留出集 ——")
    for r in rows:
        flag = "✓" if r["hit"] else "✗"
        print(f"  {flag} {r['expect']:5} p={r['p']:.3f}  {r['text'][:38]}")
    print(f"\n诈骗 {len(fraud)} 条，漏报 {len(miss)} 条（{len(miss) / len(fraud) * 100:.1f}%）")
    print(f"正常 {len(normal)} 条，误报 {len(falarm)} 条（{len(falarm) / len(normal) * 100:.1f}%）")

    ckpt = os.path.join(MODEL_DIR, "finetuned.ckpt")
    ms.save_checkpoint(net, ckpt)
    print(f"[保存] {ckpt}")

    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    print(f"明细 → {OUT_JSON}")


if __name__ == "__main__":
    main()
