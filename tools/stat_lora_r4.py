#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
银盾 · 第四轮 LoRA 评测统计（计划 §3.8 / §3.9）
====================================================================
把 LoRA 的结果与原始模型基线逐条对齐，两个口径都报，并直接判 3.9 的
四条成功标准过没过。

    统计 TAF-200 音频：
      python3 stat_lora_r4.py taf200 --lora a.jsonl [--base b.jsonl]

    统计 30 通真人（音频或文字）：
      python3 stat_lora_r4.py real30 --lora c.jsonl

口径说明（沿用前几轮，不要混用）：
  · 模型结论   = is_fraud 字段
  · p≥0.85     = 判「是」时 p=confidence，判「否」时 p=1−confidence
  两个口径必须并列报：只看其中一个会得出相反结论（见第三轮 v3 的教训）。
"""

import argparse
import json
import os
import statistics
import sys
from collections import Counter

BASE_768 = os.path.expanduser("~/taf200/base_taf200_audio_768.jsonl")
BASE_1536 = os.path.expanduser("~/taf200/base_taf200_audio.jsonl")


def load(path):
    out = {}
    for line in open(os.path.expanduser(path), encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        r = json.loads(line)
        key = r.get("file") or r.get("id") or r.get("name")
        out[key] = r
    return out


def verdict(r):
    if r.get("is_fraud") is None:
        return None
    return "fraud" if r["is_fraud"] else "normal"


def pval(r):
    p = r.get("p")
    return float(p) if p is not None else None


def rates(rows, label):
    """返回 (模型结论召回/误报, p≥0.85 召回/误报)"""
    tgt = [r for r in rows if r.get("label") == label]
    if not tgt:
        return None
    vc = sum(1 for r in tgt if verdict(r) == "fraud")
    pc = sum(1 for r in tgt if (pval(r) or 0) >= 0.85)
    return vc, pc, len(tgt)


def fmt(n, d):
    return f"{n}/{d}" if d else "—"


def latency(rows):
    ms = sorted((r.get("ms") or 0) / 1000 for r in rows if r.get("ms"))
    if not ms:
        return None
    over = sum(1 for x in ms if x > 60)
    return {"median": statistics.median(ms), "p90": ms[int(len(ms) * 0.9)],
            "max": max(ms), "over60": over, "n": len(ms)}


def cmd_taf200(a):
    lora = load(a.lora)
    print(f"[i] LoRA 结果 {len(lora)} 条：{a.lora}")
    base = load(a.base or BASE_768)
    print(f"[i] 基线 {len(base)} 条：{a.base or BASE_768}\n")

    for tag, src in (("LoRA", lora), ("基线", base)):
        f = rates(list(src.values()), "fraud")
        n = rates(list(src.values()), "normal")
        if not f or not n:
            print(f"{tag}: 标签不全，跳过")
            continue
        lat = latency(list(src.values()))
        print(f"=== {tag} ===")
        print(f"  模型结论  诈骗召回 {fmt(f[0], f[2])}   正常误报 {fmt(n[0], n[2])}")
        print(f"  p≥0.85    诈骗召回 {fmt(f[1], f[2])}   正常误报 {fmt(n[1], n[2])}")
        if lat:
            print(f"  耗时      中位 {lat['median']:.1f}s  P90 {lat['p90']:.1f}s  "
                  f"最长 {lat['max']:.1f}s  超60s {lat['over60']}/{lat['n']}")
        print()

    # 逐条对比
    common = set(lora) & set(base)
    flip = []
    for k in sorted(common):
        va, vb = verdict(lora[k]), verdict(base[k])
        if va and vb and va != vb:
            flip.append((k, vb, va, pval(base[k]), pval(lora[k]), lora[k].get("label")))
    print(f"=== 判定翻转 {len(flip)} 条（共 {len(common)} 条可比）===")
    for k, vb, va, pb, pa, lab in flip[:25]:
        print(f"  {str(lab):6s} {vb}→{va}  p {pb}→{pa}  {str(k)[:46]}")
    if len(flip) > 25:
        print(f"  … 另有 {len(flip) - 25} 条")

    same = sum(1 for k in common
               if pval(lora[k]) is not None and pval(base[k]) is not None
               and abs(pval(lora[k]) - pval(base[k])) < 1e-9)
    both = sum(1 for k in common
               if pval(lora[k]) is not None and pval(base[k]) is not None)
    print(f"\n=== p 值逐条对比 ===\n  完全相同 {same}/{both}")

    # 3.9 判据
    print("\n=== §3.9 成功标准（TAF-200 音频部分）===")
    checks = []

    def add(name, ok, val, need):
        checks.append((name, ok, val, need))
        print(f"  {'✅' if ok else '❌'} {name}: {val}（要求 {need}）")

    lf, ln = rates(list(lora.values()), "fraud"), rates(list(lora.values()), "normal")
    bf, bn = rates(list(base.values()), "fraud"), rates(list(base.values()), "normal")
    if all([lf, ln, bf, bn]):
        # 计划 §3.9 写的是对 1536 基线（92/12）；但线上 11-26 已切 768（94/12），
        # 所以两个基线都算：前者是"计划判据"，后者是"换线上值不值"的参考。
        for btag, bpath in (("1536", BASE_1536), ("768", BASE_768)):
            b2 = load(bpath) if os.path.exists(os.path.expanduser(bpath)) else None
            if not b2:
                continue
            f2, n2 = rates(list(b2.values()), "fraud"), rates(list(b2.values()), "normal")
            if not (f2 and n2):
                continue
            ok = lf[1] >= f2[1] and ln[1] <= n2[1] and (lf[1] > f2[1] or ln[1] < n2[1])
            label = "计划判据" if btag == "1536" else "换线参考"
            add(f"p≥0.85 vs {btag}（{label}）", ok,
                f"召回 {lf[1]} 误报 {ln[1]}", f"召回 ≥{f2[1]}、误报 ≤{n2[1]}、且至少一项严格更好")
    lat_l, lat_b = latency(list(lora.values())), latency(list(base.values()))
    if lat_l and lat_b:
        add("超时条数", lat_l["over60"] <= lat_b["over60"],
            f"{lat_l['over60']}/{lat_l['n']}", f"≤ 基线 {lat_b['over60']}（相近负载下）")

    ok_all = all(c[1] for c in checks)
    print(f"\n{'✅ TAF-200 音频这一关通过' if ok_all else '❌ TAF-200 音频这一关未通过'}（还有真人 30 通和产品链路两关）")
    return 0 if ok_all else 1


def cmd_real30(a):
    rows = list(load(a.lora).values())
    print(f"[i] {len(rows)} 条：{a.lora}\n")
    f, n = rates(rows, "fraud"), rates(rows, "normal")
    if not f or not n:
        print("[X] 标签不全（需要 fraud / normal）")
        return 1
    print(f"=== 模型结论 ===\n  诈骗召回 {fmt(f[0], f[2])}   正常误报 {fmt(n[0], n[2])}")
    print(f"=== p≥0.85 ===\n  诈骗召回 {fmt(f[1], f[2])}   正常误报 {fmt(n[1], n[2])}")
    lat = latency(rows)
    if lat:
        print(f"=== 耗时 ===\n  中位 {lat['median']:.1f}s  P90 {lat['p90']:.1f}s  超60s {lat['over60']}/{lat['n']}")

    # 3.9 只看音频侧；文字侧只记录
    mode = a.mode or "audio"
    if mode == "audio":
        print("\n=== §3.9 成功标准（真人 30 通音频）===")
        ok1 = f[0] == f[2]
        ok2 = n[1] == 0
        print(f"  {'✅' if ok1 else '❌'} 诈骗 15/15: {fmt(f[0], f[2])}")
        print(f"  {'✅' if ok2 else '❌'} 正常 p≥0.85 误报 0/15: {fmt(n[1], n[2])}")
        return 0 if (ok1 and ok2) else 1
    print("\n（文字模式只作参考，不进 3.9 判据）")
    return 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    p1 = sub.add_parser("taf200")
    p1.add_argument("--lora", required=True)
    p1.add_argument("--base", default="")
    p1.set_defaults(func=cmd_taf200)

    p2 = sub.add_parser("real30")
    p2.add_argument("--lora", required=True)
    p2.add_argument("--mode", default="audio", choices=["audio", "text"])
    p2.set_defaults(func=cmd_real30)

    a = ap.parse_args()
    return a.func(a)


if __name__ == "__main__":
    sys.exit(main())
