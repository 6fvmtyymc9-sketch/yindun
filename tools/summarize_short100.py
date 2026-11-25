# -*- coding: utf-8 -*-
"""短文本 100 条（A 50 诈骗 + B 50 正常）结果汇总。

用法：
    python tools/summarize_short100.py \
        --a cloud/test/lora_20261123/r4/product_a50_result.jsonl \
        --b cloud/test/lora_20261123/r4/product_b50_result.jsonl

两个 jsonl 均来自 tools/eval_product_short.py（线上 /api/report dryRun=1）。

口径说明（重要，写材料时别混）：
  - 召回        = A 组 isFraud=true 的比例
  - 误报        = B 组 isFraud=true 的比例（B 组全是真短信，判诈骗即误杀）
  - 平衡准确率  = (召回 + (1-误报)) / 2
  - S4 才是真的盖屏拦截（App 侧 Judge.java:915），S1/S2 只是提示
  - A 组短信按设计「只有诱饵、不索款」，落在 S1「套近乎」是分级正确，不是识别失败
"""
import argparse
import json
import sys
from collections import Counter


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if d.get("ok"):
                rows.append(d)
    return rows


def pct(a, b):
    return f"{a}/{b} = {a / b * 100:.0f}%" if b else f"{a}/0 = n/a"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True, help="A 组（诈骗）结果 jsonl")
    ap.add_argument("--b", required=True, help="B 组（正常）结果 jsonl")
    args = ap.parse_args()

    a = load(args.a)
    b = load(args.b)
    if not a or not b:
        print("结果为空，检查路径", file=sys.stderr)
        return 1

    hit_a = sum(1 for r in a if r["isFraud"])
    hit_b = sum(1 for r in b if r["isFraud"])
    rec = hit_a / len(a)
    fpr = hit_b / len(b)

    print(f"A 组（诈骗）{len(a)} 条，B 组（正常）{len(b)} 条")
    print()
    print(f"召回          {pct(hit_a, len(a))}")
    print(f"误报          {pct(hit_b, len(b))}")
    print(f"平衡准确率    {(rec + (1 - fpr)) / 2 * 100:.1f}%")
    total = len(a) + len(b)
    print(f"判诈骗总数    {pct(hit_a + hit_b, total)}（平衡集理想 50%）")
    print()

    print("stage 分布（S0 无风险 / S1 套近乎 / S2 吓唬 / S3 隔离 / S4 要钱）")
    for name, rows in (("A 组", a), ("B 组", b)):
        c = Counter(r.get("stage") for r in rows)
        line = "  ".join(f"S{k} {c.get(k, 0):>2}" for k in range(5))
        print(f"  {name}  {line}")
    print(f"  会盖屏（S4）：A 组 {sum(1 for r in a if (r.get('stage') or 0) >= 4)} 条 / "
          f"B 组 {sum(1 for r in b if (r.get('stage') or 0) >= 4)} 条")
    print()

    miss = [r for r in a if not r["isFraud"]]
    if miss:
        print(f"漏报 {len(miss)} 条：")
        by_type = Counter(r.get("type") for r in miss)
        for r in miss:
            print(f"  {r['id']:<5} [{r.get('type')}] stage={r.get('stage')}  {(r.get('reason') or '')[:36]}")
        print("  按类型：" + "　".join(f"{k} {v}" for k, v in by_type.most_common()))
        print()

    fa = [r for r in b if r["isFraud"]]
    if fa:
        print(f"误杀 {len(fa)} 条（其中 S4 会弹红屏 {sum(1 for r in fa if (r.get('stage') or 0) >= 4)} 条）：")
        for r in fa:
            print(f"  {r['id']:<5} [{r.get('type')}] stage={r.get('stage')}  {r.get('text', '')[:34]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
