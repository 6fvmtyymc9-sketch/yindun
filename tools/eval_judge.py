#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 /judge 批量打分文字样本，输出逐条结果和汇总。微调前后各跑一次，逐条对比。

输入 jsonl：每行 {"text", "label": fraud|normal, "id"?}
    - CGoDial 留出集：build_judge_data.py 生成的 heldout_cgodial.jsonl
    - holdout.json：加 --holdout 参数直接读 cloud/test/holdout.json

用法：
    python3 tools/eval_judge.py --url http://127.0.0.1:8000 \
        --input ~/work/<名字>/data/heldout_cgodial.jsonl --out ~/work/<名字>/eval/base_cgodial.jsonl
    python3 tools/eval_judge.py --url http://127.0.0.1:8001 --holdout cloud/test/holdout.json \
        --out ~/work/<名字>/eval/lora_holdout.jsonl

汇总同时给两种口径，报告里两个都要写：
    verdict  模型自己的 is_fraud 结论
    p>=0.85  按融合规则的抬分线
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from collections import Counter


def post(url, body):
    req = urllib.request.Request(url + "/judge", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        return json.loads(urllib.request.urlopen(req, timeout=600).read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read() or b"{}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8000")
    ap.add_argument("--input")
    ap.add_argument("--holdout", help="cloud/test/holdout.json")
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()

    rows = []
    if a.holdout:
        for i, c in enumerate(json.load(open(a.holdout, encoding="utf-8"))["cases"]):
            rows.append({"id": f"holdout#{i}", "text": c["text"], "label": c["expect"]})
    if a.input:
        for line in open(os.path.expanduser(a.input), encoding="utf-8"):
            if line.strip():
                rows.append(json.loads(line))
    if a.limit:
        rows = rows[: a.limit]
    if not rows:
        sys.exit("没有样本")

    os.makedirs(os.path.dirname(os.path.abspath(os.path.expanduser(a.out))), exist_ok=True)
    c_v, c_p = Counter(), Counter()
    with open(os.path.expanduser(a.out), "w", encoding="utf-8") as fo:
        for i, r in enumerate(rows, 1):
            t0 = time.time()
            res = post(a.url, {"text": r["text"]})
            lab = "fraud" if str(r["label"]).lower() in ("fraud", "诈骗") else "normal"
            p = res.get("p")
            pv = "unknown" if p is None else ("fraud" if p >= 0.85 else "normal")
            c_v[(lab, res.get("verdict", "error"))] += 1
            c_p[(lab, pv)] += 1
            fo.write(json.dumps({"id": r.get("id"), "label": lab, **{k: res.get(k) for k in
                     ("verdict", "p", "confidence", "reason", "scene", "parsedFrom", "ms", "skipped")}},
                                ensure_ascii=False) + "\n")
            fo.flush()
            print(f"[{i}/{len(rows)}] {lab:6s} -> {res.get('verdict')} p={p} {time.time() - t0:.1f}s", flush=True)

    def table(c, name):
        f_tot = sum(v for (l, _), v in c.items() if l == "fraud")
        n_tot = sum(v for (l, _), v in c.items() if l == "normal")
        print(f"\n[{name}] 诈骗判对 {c[('fraud', 'fraud')]}/{f_tot}，正常误报 {c[('normal', 'fraud')]}/{n_tot}，"
              f"未出结论 {sum(v for (l, x), v in c.items() if x in ('unknown', 'error'))}")
    table(c_v, "模型结论 verdict")
    table(c_p, "p>=0.85")


if __name__ == "__main__":
    main()
