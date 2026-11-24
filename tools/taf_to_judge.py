#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 TeleAntiFraud 的 ASR 转录结果转成 build_judge_data.py 能吃的 jsonl。

⚠️ 标签怎么来的（务必写进报告）：
    audio.zip 的目录名 POS-* / NEG-* 与内容整体相反——
        NEG-*（名义"正常"）= 京东退款、无抵押贷款、年化50%理财、中奖500万、车祸手术费……
        POS-*（名义"诈骗"）= 租车取车、订料理发、外卖确认、家政报价、家电维修……
    因此本脚本按**内容侧**取用：
        正常对话 ← POS-* 组（订餐/外卖/租车/理发/家政/维修等日常电话）
        诈骗对话 ← NEG-* 组（各类诈骗话术）
    这也是老师说的"公开发布的数据打包有问题"的另一面（sft.zip 里 is_fraud 全 true）。

用法：
    python3 taf_to_judge.py --fraud ~/taf/transcripts.jsonl \
                            --normal ~/taf/transcripts_pos.jsonl \
                            --outdir ~/work/taf_jsonl
输出：
    taf_fraud.jsonl   {"text","label":"fraud"}
    taf_normal.jsonl  {"text","label":"normal","biz":...}
    taf_stats.json    条数、长度分布、被丢弃原因
"""
import argparse
import json
import os
import re
from collections import Counter

MIN_CHARS = 60
BIZ_RULES = [
    (("订餐", "外卖", "送餐", "菜单", "菜品"), "订餐配送"),
    (("租车", "取车", "车型", "试驾", "购车", "4S"), "用车与购车"),
    (("理发", "美发", "剪发"), "美发预约"),
    (("家政", "保洁", "打扫"), "家政保洁"),
    (("维修", "上门", "师傅", "家电"), "家电维修"),
    (("招聘", "面试", "岗位", "薪资"), "招聘咨询"),
    (("快递", "包裹", "物流", "派送"), "快递收发"),
    (("银行", "客服", "业务", "办理"), "业务咨询"),
]


def guess_biz(t):
    for kws, biz in BIZ_RULES:
        if any(k in t for k in kws):
            return biz
    return "日常事务"


def load(path, want, dedup):
    out, drop = [], Counter()
    if not os.path.exists(path):
        return out, drop
    for line in open(path, encoding="utf-8"):
        if not line.strip():
            continue
        d = json.loads(line)
        if d.get("err"):
            drop["asr_err"] += 1
            continue
        t = re.sub(r"\s+", "", d.get("text") or "")
        t = re.sub(r"^[，。、；：,.\s]+", "", t)
        if len(t) < MIN_CHARS:
            drop["too_short"] += 1
            continue
        if t in dedup:
            drop["dup"] += 1
            continue
        dedup.add(t)
        rec = {"text": t, "label": want}
        if want == "normal":
            rec["biz"] = guess_biz(t)
        out.append(rec)
    return out, drop


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fraud", default="/home/zkpk/taf/transcripts.jsonl")
    ap.add_argument("--normal", default="/home/zkpk/taf/transcripts_pos.jsonl")
    ap.add_argument("--outdir", default="/home/zkpk/work/taf_jsonl")
    a = ap.parse_args()
    os.makedirs(a.outdir, exist_ok=True)

    seen = set()
    fr, d1 = load(a.fraud, "fraud", seen)
    nm, d2 = load(a.normal, "normal", seen)

    for name, rows in (("taf_fraud.jsonl", fr), ("taf_normal.jsonl", nm)):
        with open(os.path.join(a.outdir, name), "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")

    stats = {"fraud": len(fr), "normal": len(nm), "drop_fraud": dict(d1), "drop_normal": dict(d2),
             "biz_dist": dict(Counter(r["biz"] for r in nm).most_common())}
    json.dump(stats, open(os.path.join(a.outdir, "taf_stats.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(json.dumps(stats, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
