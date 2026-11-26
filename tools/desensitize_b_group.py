#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B 组真实短信脱敏（第 4 轮短文本-100）。

背景：B 组 50 条取自家人真实手机短信，仓库是公开的。需要去掉可识别信息。
要求（SFT接入计划.md 三之二）：
  1. 卡号尾号、订单号改成星号；
  2. 「素材来源」列统一写「真实短信」，不写「家人手机」「家庭群聊」等；
  3. 相关 jsonl 和文档里的原文同步改。

做法：**等长星号替换**——4 位尾号换 4 个 *，8 位订单号换 8 个 *。
理由：字数分布和判定结论都不受影响（评测基线是在脱敏前跑的，等长替换才能说
「只是把标识符遮掉，别的一个字没动」）。

用法：
  python3 tools/desensitize_b_group.py --check   # 只报告会改什么
  python3 tools/desensitize_b_group.py --write   # 落盘
"""
import argparse
import csv
import json
import os
import re

R4 = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                  "cloud", "test", "lora_20261123", "r4")

CSV_FILES = [
    "短文本B组50_成品_20261125.csv",
    "短文本100条_成品_20261125.csv",
]
JSONL_FILES = [
    "B组50_for_judge.jsonl",
    # 评测产物里也嵌着原文与 signals，一并清
    "product_b50_result.jsonl",
    "base_b50_sft_result.jsonl",
]

# 卡号尾号 / 订单号 / 运单号 / 取件码 / 售后单号（长的先替，避免短串截断长串）
NUMBERS = [
    "37295108", "7841529", "2950471", "604271", "361947", "516382",
    "847290", "6284197", "591036",
    "81742", "27461", "73629", "48291", "19472",
    "9111", "9118", "0278", "2460", "1629", "5019", "0980", "6097",
    "2941", "7420", "4713", "8250", "3691", "5174", "2852", "6039",
    "7412", "4162", "3719", "6143", "8341", "4729",
    "6392", "1472", "2581", "3619", "5026", "7283",
]
# 人名
NAMES = [("王某成", "家里人")]

SOURCE_COL = "素材来源"


def mask(text):
    """把可识别数字按原长度替换为星号；人名直接换词。"""
    for n in NUMBERS:
        text = text.replace(n, "*" * len(n))
    for old, new in NAMES:
        text = text.replace(old, new)
    return text


def process_csv(path, write):
    changes = []
    with open(path, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
        fields = list(rows[0].keys()) if rows else []

    for r in rows:
        if not r.get("编号", "").startswith("B"):
            continue
        for col in ("正文", "判断依据", "备注"):
            if col in r and r[col]:
                new = mask(r[col])
                if new != r[col]:
                    changes.append((r["编号"], col, r[col], new))
                    r[col] = new
        if SOURCE_COL in r and r[SOURCE_COL] != "真实短信":
            changes.append((r["编号"], SOURCE_COL, r[SOURCE_COL], "真实短信"))
            r[SOURCE_COL] = "真实短信"

    if write and changes:
        with open(path, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fields)
            w.writeheader()
            w.writerows(rows)
    return changes


def _scrub(obj, changes, path=""):
    """递归清洗所有字符串值（jsonl 的字段结构不统一，只能全扫）。"""
    if isinstance(obj, str):
        new = mask(obj)
        if new != obj:
            changes.append((path, "", obj, new))
        return new
    if isinstance(obj, list):
        return [_scrub(x, changes, path) for x in obj]
    if isinstance(obj, dict):
        return {k: _scrub(v, changes, k) for k, v in obj.items()}
    return obj


def process_jsonl(path, write):
    changes = []
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            d = json.loads(line)
            out.append(_scrub(d, changes))
    if write and changes:
        with open(path, "w", encoding="utf-8") as f:
            for d in out:
                f.write(json.dumps(d, ensure_ascii=False) + "\n")
    return changes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    write = a.write and not a.check

    total = 0
    for name in CSV_FILES + JSONL_FILES:
        path = os.path.join(R4, name)
        if not os.path.exists(path):
            print(f"跳过（不存在）{name}")
            continue
        ch = process_csv(path, write) if name.endswith(".csv") else process_jsonl(path, write)
        total += len(ch)
        print(f"\n=== {name}：{len(ch)} 处{'（已写入）' if write else '（仅检查）'}")
        for cid, col, old, new in ch[:3]:
            print(f"  {cid} [{col}] {old[:52]}")
            print(f"       -> {new[:52]}")
        if len(ch) > 3:
            print(f"  …… 另有 {len(ch)-3} 处")
    print(f"\n合计 {total} 处")


if __name__ == "__main__":
    main()
