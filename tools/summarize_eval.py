#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把评测 jsonl 汇总成「两个口径」的表格（模型结论 vs p≥0.85）。

用法：
    python3 summarize_eval.py eval/r3_cgodial.jsonl eval/r3_holdout.jsonl \
        eval/r3_eval30_text.jsonl eval/r3_eval30_audio.jsonl

每行文件输出：n / 正常误报(结论) / 正常误报(p≥.85) / 诈骗召回(结论) / 诈骗召回(p≥.85) / p 值分布
"""
import json
import sys
from collections import Counter

TH = 0.85


def stat(path):
    rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    rows = [r for r in rows if not r.get("skipped")]
    norm = [r for r in rows if r.get("label") == "normal"]
    frad = [r for r in rows if r.get("label") == "fraud"]

    def is_f(r):
        return r.get("verdict") == "fraud" or r.get("is_fraud") is True

    def big_p(r):
        return (r.get("p") or 0) >= TH

    return {
        "file": path, "n": len(rows),
        "normal": len(norm), "fraud": len(frad),
        "fa_v": sum(1 for r in norm if is_f(r)),
        "fa_p": sum(1 for r in norm if big_p(r)),
        "tp_v": sum(1 for r in frad if is_f(r)),
        "tp_p": sum(1 for r in frad if big_p(r)),
        "p_top": Counter(round(r.get("p") or 0, 2) for r in rows).most_common(5),
        "err": sum(1 for r in rows if r.get("err")),
    }


def main():
    files = sys.argv[1:]
    if not files:
        print(__doc__)
        raise SystemExit(1)
    print(f"{'文件':34s} {'n':>4s} {'正常误报':>10s} {'(p≥.85)':>9s} {'诈骗召回':>9s} {'(p≥.85)':>9s}")
    for f in files:
        s = stat(f)
        print(f"{s['file']:34s} {s['n']:4d} "
              f"{s['fa_v']:>5d}/{s['normal']:<4d} {s['fa_p']:>4d}/{s['normal']:<4d} "
              f"{s['tp_v']:>4d}/{s['fraud']:<4d} {s['tp_p']:>4d}/{s['fraud']:<4d}")
        print(f"    p 分布 {s['p_top']}" + (f"  错误 {s['err']}" if s["err"] else ""))


if __name__ == "__main__":
    main()
