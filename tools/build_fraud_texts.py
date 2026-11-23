#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 Telecom_Fraud_Texts_8 的 8 个 label CSV 里取 5 类诈骗，转成 build_judge_data.py 要的 jsonl。

    {"text": "...", "label": "fraud", "type": "冒充公检法", "reason": "..."}

为什么只要 5 类：
    老师第二版计划写「诈骗文字（sft.zip / Telecom_Fraud_Texts_8 取 5 类）」。
    选这 5 类的标准是「电话里真的会发生」——公检法、贷款征信、客服退款、冒充熟人、刷单返利
    都是通话类骗术；label6（交友）和 label7（游戏皮肤）主要是短信/社交场景，留着会稀释信号。

label0（17,048 条营销短信）不取：
    正常样本统一用 CGoDial 真实政务热线。营销短信是「不诈骗但也让人烦」的另一类，
    混进来会让模型学到「提到抽奖/链接就判诈骗」，反而抬高真实场景误报。

用法：
    PYTHONPATH= python tools/build_fraud_texts.py \
        --src "外部数据集/Telecom_Fraud_Texts_8/Telecom_Fraud_Texts_8-main" \
        --out "外部数据集/judge_data_20261123/fraud_texts.jsonl"
"""
import argparse
import csv
import json
import os
import re

# label -> (类型名, 判据话术里的关键词)
CLASSES = {
    "1": ("冒充公检法", "冒充公安、检察院等机关，以涉嫌犯罪、资金清查为由要求配合调查或转账"),
    "2": ("虚假贷款·征信修复", "以贷款、征信修复为名，要求先交费用或通过借款验证还款能力"),
    "3": ("冒充客服退款", "冒充平台客服，以退款、理赔为名诱导操作账户或提供验证码"),
    "4": ("冒充熟人代购", "冒充老师、领导或亲友，以代购、垫付、急用钱为由要求转账"),
    "5": ("刷单返利", "以点赞、刷单、做任务返利为名，诱导下载软件或先垫付资金"),
}

MIN_CHARS = 60


def clean(t):
    t = re.sub(r"\s+", "", t or "")
    return t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="Telecom_Fraud_Texts_8-main 目录")
    ap.add_argument("--out", required=True)
    ap.add_argument("--per-class", type=int, default=3000, help="每类上限，防某类过载")
    a = ap.parse_args()

    os.makedirs(os.path.dirname(os.path.abspath(a.out)) or ".", exist_ok=True)
    seen, rows, stats = set(), [], {}
    for lab, (kind, reason) in CLASSES.items():
        f = os.path.join(a.src, f"label{lab}-new1030.csv")
        if not os.path.exists(f):
            print(f"[!] 缺文件 {f}")
            continue
        kept = dropped = 0
        for r in csv.DictReader(open(f, encoding="utf-8-sig")):
            t = clean(r.get("content"))
            if len(t) < MIN_CHARS:          # build_judge_data 也要求 >=60 字，先对齐免得白算
                dropped += 1
                continue
            if t in seen:
                continue
            seen.add(t)
            rows.append({"text": t, "label": "fraud", "type": kind, "reason": reason})
            kept += 1
            if kept >= a.per_class:
                break
        stats[kind] = {"kept": kept, "too_short": dropped}

    with open(a.out, "w", encoding="utf-8") as fh:
        for x in rows:
            fh.write(json.dumps(x, ensure_ascii=False) + "\n")

    total = len(rows)
    lens = sorted(len(x["text"]) for x in rows)
    print(json.dumps({
        "out": a.out, "total": total, "by_type": stats,
        "chars": {"min": lens[0] if lens else 0, "median": lens[len(lens) // 2] if lens else 0,
                  "max": lens[-1] if lens else 0},
    }, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
