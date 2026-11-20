#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
修复 sft.zip 的标注结构 bug —— 让「不是诈骗」这个答案第一次进入 loss
=====================================================================
背景（2026-11-20 实测，见 docs/SFT恒答是根因排查_20261120.md）：

  sft.zip 里每条样本的 messages 末尾都是 user，没有对应的 assistant；
  真正的标签放在独立的 `answers` 字段，而 ms-swift 只读 messages。
  结果：

      "is_fraud": true   出现 7,177 次（含 train+test）
      "is_fraud": false  出现 0 次        ← 模型从未学过「否」

  所以模型恒答「是」，不是权重坏，是负样本的答案根本没进训练。

结构 × 标签的精确交叉表（train+test，33,953 条）：

    SU            场景名（订餐/客服/预约/交通/购物/打车/外卖）  13,388  零 loss
    SUAU          fraud                                        7,177  只训了第 1 轮
    SUAU          normal                                       6,211  只训了第 1 轮
    SUAUAU        具体诈骗类型                                  7,177  三轮全有

  并且 SUAU-fraud 与 SUAUAU 的 audios **完全相同**（train 里各 5,724，
  交集 5,724）——同一批通话被做了两种问法，正样本重复计数。

本脚本做什么
------------
  1. SU       + 场景名 → 末尾补一条 assistant，输出 scene JSON
  2. SUAU     + fraud  → 末尾补一条 assistant，输出 is_fraud:true
  3. SUAU     + normal → 末尾补一条 assistant，输出 **is_fraud:false**
  4. SUAUAU            → 原样保留
  5. 去重：默认丢掉 SUAU-fraud（与 SUAUAU 同批通话），避免正样本训两遍
     （--keep-dup-fraud 可关掉这个行为）

修完后的训练集：约 21,422 条，正负 5,724 : 4,987（≈53:47，本来就平衡，
说明"ThisAntiFraud 正样本占绝对多数"这个旧说法也是错的）。

用法
----
  set PYTHONPATH=
  python tools/fix_sft_data.py                       # 输出到 D:/华为ict/外部数据集/sft_fixed
  python tools/fix_sft_data.py --keep-dup-fraud      # 保留与 3 轮重复的 2 轮正样本
"""
import argparse
import collections
import io
import json
import os
import zipfile

ZIP = r"D:/下载/sft.zip"
OUT = r"D:/华为ict/外部数据集/sft_fixed"

SCENES = {"订餐服务", "咨询客服", "预约服务", "交通咨询", "日常购物", "打车服务", "外卖服务"}
FRAUD_TYPES = {"客服诈骗", "银行诈骗", "投资诈骗", "钓鱼诈骗", "彩票诈骗", "绑架诈骗", "身份盗窃"}

REASON = {
    "fraud": "通话中出现冒充客服或公检法、要求转账汇款、诱导点击链接、索要验证码或银行卡信息等典型诈骗话术特征。",
    "normal": "通话内容为正常的业务咨询、订餐外卖或售后沟通，未出现索要钱财、诱导转账、冒充公检法等诈骗特征。",
}


def scene_reply(name):
    return (
        '\n\n```json\n{\n  "scene": "%s",\n  "reason": "根据通话双方的用语、诉求与业务上下文，判断属于%s场景。",\n'
        '  "confidence": 0.9\n}\n```' % (name, name)
    )


def fraud_reply(is_fraud):
    key = "fraud" if is_fraud else "normal"
    return (
        '\n\n```json\n{\n  "reason": "%s",\n  "confidence": 0.9,\n  "is_fraud": %s\n}\n```'
        % (REASON[key], "true" if is_fraud else "false")
    )


def roles_of(x):
    return "".join({"system": "S", "user": "U", "assistant": "A"}[m["role"]] for m in x["messages"])


def classify(x):
    """返回 (类别, 要不要补的 assistant 内容 或 None)"""
    r = roles_of(x)
    a = str(x.get("answers"))
    if r == "SUAUAU":
        return "SUAUAU", None
    if r == "SU" and a in SCENES:
        return "SU-scene", scene_reply(a)
    if r == "SUAU" and a == "fraud":
        return "SUAU-fraud", fraud_reply(True)
    if r == "SUAU" and a == "normal":
        return "SUAU-normal", fraud_reply(False)
    return "UNKNOWN", None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", default=ZIP)
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--keep-dup-fraud", action="store_true",
                    help="保留与 SUAUAU 同一批通话的 2 轮正样本（默认丢弃）")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    z = zipfile.ZipFile(a.zip)
    stat = {}
    for split, name in (("sft/train.jsonl", "train"), ("sft/test.jsonl", "test")):
        rows = [json.loads(l) for l in io.TextIOWrapper(z.open(split), encoding="utf-8")]

        # 第一遍：哪些 audios 已经有 3 轮完整版本（含 is_fraud 答案）
        three_round = {tuple(x.get("audios") or ()) for x in rows if classify(x)[0] == "SUAUAU"}

        out = []
        stat[name] = collections.Counter()
        for x in rows:
            kind, reply = classify(x)
            stat[name][kind] += 1

            if kind == "UNKNOWN":
                stat[name]["丢弃-未知结构"] += 1
                continue

            au = tuple(x.get("audios") or ())
            if (not a.keep_dup_fraud) and kind == "SUAU-fraud" and au in three_round:
                stat[name]["丢弃-与3轮重复的正样本"] += 1
                continue

            if reply is not None:
                x["messages"] = list(x["messages"]) + [{"role": "assistant", "content": reply}]
            out.append(x)

        p = os.path.join(a.out, f"{name}.jsonl")
        with open(p, "w", encoding="utf-8") as f:
            for x in out:
                f.write(json.dumps(x, ensure_ascii=False) + "\n")
        stat[name]["写入"] = len(out)

    # 统计写入后 is_fraud 的真实分布
    print("=" * 66)
    for name in ("train", "test"):
        print(f"[{name}]")
        for k, v in stat[name].most_common():
            print(f"   {k:24s} {v:>7d}")
        p = os.path.join(a.out, f"{name}.jsonl")
        t = f = 0
        last_asst = 0
        for line in open(p, encoding="utf-8"):
            x = json.loads(line)
            if x["messages"][-1]["role"] == "assistant":
                last_asst += 1
            for m in x["messages"]:
                if m["role"] != "assistant":
                    continue
                c = m["content"]
                if '"is_fraud": true' in c:
                    t += 1
                elif '"is_fraud": false' in c:
                    f += 1
        print(f"   → 末尾是 assistant（有 loss）: {last_asst}")
        print(f"   → is_fraud:true  {t}")
        print(f"   → is_fraud:false {f}   ← 修复前是 0")
        print()
    print(f"输出目录：{a.out}")


if __name__ == "__main__":
    main()
