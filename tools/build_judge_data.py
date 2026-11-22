#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把文字样本整理成 /judge 原生两轮格式的 LoRA 训练数据（ms-swift 的 messages 格式）。

    正常样本：CGoDial 真实政务热线（text_asr 字段，ASR 识别文字）
    诈骗样本 / 其他正常样本：你自己准备的 jsonl，每行 {"text", "label", "type"?, "reason"?}

输出（都在 --out 目录下）：
    train.jsonl / val.jsonl     训练用，两类数量相等
    heldout_cgodial.jsonl       留出的 CGoDial 真实对话，**永远不参与训练**，第 5、7 步用它算政务热线误报率
    summary.json                各来源条数、长度分布，写报告用

用法（在学校 GPU 容器里）：
    python3 ~/yindun/tools/build_judge_data.py \
        --cgodial ~/data/cgodial/cgodial_flow_dialogs.jsonl \
        --extra ~/work/<名字>/fraud_texts.jsonl \
        --out ~/work/<名字>/data --per-class 1000

几个刻意的处理（每一条都是为了不让模型「抄近路」）：
    1. 去掉「客服：」「来电人：」这类角色标签。线上 SIS 转写本来就没有说话人，
       而且只有 CGoDial 带标签，不去掉模型会学成「有标签 = 正常」
    2. 长度统一截到 60~800 字，避免「长 = 正常、短 = 诈骗」
    3. 两类第一轮场景答案一律写「咨询客服」，场景不泄露标签
    4. 留出集按场景分层抽样、按 id 去重，训练集里出现的 id 不会进留出集
"""
import argparse
import json
import os
import random
import re
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sft_server import NATIVE_Q1_TEXT, NATIVE_Q2  # noqa: E402  与推理服务的提示词保持一字不差

ROLE = re.compile(r"^\s*[^：:\n]{1,6}[：:]\s*", re.M)
MIN_CHARS, MAX_CHARS = 60, 800
HELDOUT = {"gov": 200, "jiaoguan": 40, "etc": 40, "zhubao": 20}   # 共 300 通
BIZ = {"gov": "公积金、医保或社保", "jiaoguan": "户籍、证件或车驾管", "etc": "高速 ETC", "zhubao": "租房备案或公租房"}


def clean(text):
    t = ROLE.sub("", text or "")
    t = re.sub(r"\s+", "", t)
    return t[:MAX_CHARS]


def normal_reason(biz):
    return (f"这是一通关于{biz}的正常业务沟通，对方按流程说明办理方式，"
            "没有要求转账汇款、提供验证码或银行卡密码，也没有让下载软件、屏幕共享或点击陌生链接，不构成诈骗。")


def fraud_reason(kind):
    kind = kind or "冒充身份"
    return f"对方以「{kind}」为由，诱导对方转账汇款或提供验证码、银行卡等敏感信息，符合典型的{kind}类电信诈骗特征。"


def sample(text, is_fraud, reason, conf=0.9):
    q2 = NATIVE_Q2.replace("音频内容", "通话内容")
    r1 = json.dumps({"scene": "咨询客服", "reason": "通话双方围绕具体事项进行沟通", "confidence": 0.6},
                    ensure_ascii=False, indent=2)
    r2 = json.dumps({"reason": reason, "confidence": conf, "is_fraud": bool(is_fraud)},
                    ensure_ascii=False, indent=2)
    return {"messages": [
        {"role": "user", "content": NATIVE_Q1_TEXT.format(text=text)},
        {"role": "assistant", "content": r1},
        {"role": "user", "content": q2},
        {"role": "assistant", "content": r2},
    ]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cgodial", required=True)
    ap.add_argument("--extra", nargs="*", default=[], help="jsonl：每行 {text, label: fraud|normal, type?, reason?}")
    ap.add_argument("--out", required=True)
    ap.add_argument("--per-class", type=int, default=1000)
    ap.add_argument("--val-ratio", type=float, default=0.05)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    os.makedirs(a.out, exist_ok=True)

    # ---- CGoDial：只用 real，先切留出集
    real = []
    for line in open(os.path.expanduser(a.cgodial), encoding="utf-8"):
        d = json.loads(line)
        if d.get("kind") != "real":
            continue
        t = clean(d.get("text_asr") or d.get("text"))
        if len(t) >= MIN_CHARS:
            real.append({"id": d["id"], "scene": d["scene"], "text": t})
    rnd.shuffle(real)
    held, rest, need = [], [], dict(HELDOUT)
    for d in real:
        if need.get(d["scene"], 0) > 0:
            held.append(d)
            need[d["scene"]] -= 1
        else:
            rest.append(d)
    held_ids = {d["id"] for d in held}
    rest = [d for d in rest if d["id"] not in held_ids]

    # ---- 其他来源
    pos, neg_extra, src = [], [], Counter()
    for f in a.extra:
        for line in open(os.path.expanduser(f), encoding="utf-8"):
            if not line.strip():
                continue
            d = json.loads(line)
            t = clean(d.get("text"))
            if len(t) < MIN_CHARS:
                continue
            lab = str(d.get("label", "")).lower()
            if lab in ("fraud", "诈骗", "1", "true"):
                pos.append(sample(t, True, d.get("reason") or fraud_reason(d.get("type"))))
                src["fraud_available:" + os.path.basename(f)] += 1
            elif lab in ("normal", "正常", "0", "false"):
                neg_extra.append(sample(t, False, d.get("reason") or normal_reason("日常事务")))
                src["normal_available:" + os.path.basename(f)] += 1

    n = min(a.per_class, len(pos)) if pos else a.per_class
    if pos and len(pos) < a.per_class:
        print(f"[!] 诈骗样本只有 {len(pos)} 条，每类按 {n} 条取", file=sys.stderr)
    rnd.shuffle(pos)
    rnd.shuffle(neg_extra)
    pos = pos[:n]
    # 正常样本：外部正常最多占一半，其余用 CGoDial
    neg = neg_extra[: n // 2]
    neg += [sample(d["text"], False, normal_reason(BIZ.get(d["scene"], "办事"))) for d in rest[: n - len(neg)]]
    src["normal:cgodial_real"] = n - min(len(neg_extra), n // 2)

    data = pos + neg
    rnd.shuffle(data)
    nv = max(1, int(len(data) * a.val_ratio))
    with open(os.path.join(a.out, "val.jsonl"), "w", encoding="utf-8") as fv:
        for x in data[:nv]:
            fv.write(json.dumps(x, ensure_ascii=False) + "\n")
    with open(os.path.join(a.out, "train.jsonl"), "w", encoding="utf-8") as ft:
        for x in data[nv:]:
            ft.write(json.dumps(x, ensure_ascii=False) + "\n")
    with open(os.path.join(a.out, "heldout_cgodial.jsonl"), "w", encoding="utf-8") as fh:
        for d in held:
            fh.write(json.dumps({"id": d["id"], "scene": d["scene"], "text": d["text"], "label": "normal"},
                                ensure_ascii=False) + "\n")

    summary = {
        "train": len(data) - nv, "val": nv, "fraud": len(pos), "normal": len(neg),
        "heldout_cgodial": len(held), "heldout_by_scene": Counter(d["scene"] for d in held),
        "sources": src, "chars": [MIN_CHARS, MAX_CHARS], "seed": a.seed,
    }
    json.dump(summary, open(os.path.join(a.out, "summary.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
