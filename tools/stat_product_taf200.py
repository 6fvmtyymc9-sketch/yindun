#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统计 TAF-200 产品链路实跑结果（product_taf200.jsonl）。

口径说明（别混用）：
  1) 产品链路   = 规则 + DeepSeek + AntiFraud-SFT 三票融合后的最终 stage/level
  2) 去掉第三票 = 把 seconded（SFT 抬级）的条数扣掉，等价于只有规则+大模型
  3) SFT 单模型 = sftP >= 0.85（线上融合实际用的线）
  4) SFT 结论   = sftIsFraud（模型自己的 is_fraud）

用法：
  python3 tools/stat_product_taf200.py cloud/test/lora_20261123/r4/product_taf200.jsonl
"""
import collections
import json
import sys

PUSH = {"warn", "high", "block"}  # 会推送的级别；watch 不推送


def pct(a, b):
    return f"{a}/{b}"


def main(path):
    rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    fraud = [r for r in rows if r["label"] == "fraud"]
    normal = [r for r in rows if r["label"] == "normal"]

    print(f"总数 {len(rows)}  诈骗 {len(fraud)}  正常 {len(normal)}")
    print(f"sftState {dict(collections.Counter(str(r['sftState']) for r in rows))}")
    print(f"basis    {dict(collections.Counter(str(r['basis']) for r in rows))}")
    print(f"level    {dict(collections.Counter(str(r['level']) for r in rows))}")
    print()

    def rate(xs, pred):
        return pct(sum(1 for r in xs if pred(r)), len(xs))

    print("=== 1. 产品链路（三票融合，线上实际）===")
    print("  诈骗推送   ", rate(fraud, lambda r: r["level"] in PUSH))
    print("  正常误报   ", rate(normal, lambda r: r["level"] in PUSH))
    print("  S4 弹红屏  ", "诈骗", rate(fraud, lambda r: r["stage"] == 4),
          " 正常", rate(normal, lambda r: r["stage"] == 4))
    print()

    print("=== 2. 去掉第三票（规则 + 大模型）===")
    def no_sft(r):
        if r["level"] not in PUSH:
            return False
        return not r["seconded"]
    print("  诈骗推送   ", rate(fraud, no_sft))
    print("  正常误报   ", rate(normal, no_sft))
    print()

    print("=== 3. SFT 单模型 p>=0.85 ===")
    print("  诈骗召回   ", rate(fraud, lambda r: (r["sftP"] or 0) >= 0.85))
    print("  正常误报   ", rate(normal, lambda r: (r["sftP"] or 0) >= 0.85))
    print()

    print("=== 4. SFT 结论 is_fraud ===")
    print("  诈骗召回   ", rate(fraud, lambda r: r["sftIsFraud"] is True))
    print("  正常误报   ", rate(normal, lambda r: r["sftIsFraud"] is True))
    print()

    print("=== 5. 第三票救回（seconded）按类型 ===")
    c = collections.Counter(r["type"] for r in rows if r["seconded"])
    for k, v in c.most_common():
        print(f"  {v:3d}  {k}")
    print()

    print("=== 6. 漏报明细（诈骗未推送）===")
    for r in fraud:
        if r["level"] not in PUSH:
            print(f"  stage{r['stage']} p={r['sftP']} {r['sftState']} {r['type']} | {r['reason'][:40]}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1
         else "cloud/test/lora_20261123/r4/product_taf200.jsonl")
