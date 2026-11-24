#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
短文本-100 填写表自检 + 转评测输入。

填完 cloud/test/lora_20261123/r4/短文本100_填写表.csv 后先跑这个，全 PASS 再送评测。
写作要求见 docs/短文本100_写作说明.md。

用法：
    python3 tools/check_short100.py <填写表.csv>
    python3 tools/check_short100.py <填写表.csv> --train <train.jsonl>    # 额外查和训练集撞车
    python3 tools/check_short100.py <填写表.csv> --emit-jsonl <out.jsonl>  # 转 eval_judge.py 的输入

检查项：
    结构   100 行、A50/B50、编号连续、分组与编号前缀一致
    字数   正文 15~60 字（10~14 或 61~80 只告警；<10 或 >80 报错）
    A 组   不得出现索款/取密词（转账、验证码、链接、二维码、下载、共享屏幕……）
    B 组   必须含 ≥1 个高危词，且必须写出合法性线索
    重复   组内完全相同、或字面高度相似（bigram Jaccard > 0.75）告警
    依据   判断依据不能空（它不送模型，只防自己标错）
    撞车   --train 给定时，正文与训练集去空白标点后完全相同则报错
"""
import argparse
import csv
import json
import os
import re
import sys

# A 组禁词：这些是"下一步动作"，出现就说明写成了索款/取密类，不是 A 组的题
A_BAN = [
    "转账", "汇款", "转钱", "打款", "付款", "支付", "验证码",
    "安全账户", "冻结", "解冻", "保证金",
    "点击", "链接", "网址", "二维码", "扫码", "扫一扫", "下载", "安装",
    "APP", "app", "共享屏幕", "屏幕共享", "密码", "卡号", "身份证号",
]

# B 组必须至少命中一个，否则测不出误报
B_HIGH = [
    "验证码", "转账", "汇款", "冻结", "解冻", "安全账户", "额度", "卡号",
    "密码", "退款", "理赔", "链接", "二维码", "扫码", "涉案", "立案",
    "传票", "保证金", "身份证", "资金", "账户", "风控",
]

PUNCT = re.compile(r"[\s，。、,.!！?？；;：:\"'“”‘’()（）\[\]【】{}<>《》…—\-~·|/\\]+")


def norm(s):
    """去空白与标点，用于重复/撞车判断。"""
    return PUNCT.sub("", s or "")


def bigrams(s):
    return {s[i:i + 2] for i in range(len(s) - 1)} if len(s) >= 2 else {s}


def jaccard(a, b):
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def read_table(path):
    last = None
    for enc in ("utf-8-sig", "utf-8", "gbk"):
        try:
            with open(path, encoding=enc, newline="") as f:
                return list(csv.DictReader(f)), enc
        except UnicodeDecodeError as e:
            last = e
            continue
    raise SystemExit(f"读不了 {path}：{last}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--train", help="训练集 jsonl，用于查正文是否与训练样本完全相同")
    ap.add_argument("--emit-jsonl", help="通过检查后写出 eval_judge.py 的输入")
    ap.add_argument("--allow-partial", action="store_true", help="未填满也放行（只看已填的部分）")
    a = ap.parse_args()

    rows, enc = read_table(a.csv)
    print(f"读入 {len(rows)} 行（编码 {enc}）")

    errs, warns = [], []

    # ---- 结构 ----
    need = ["编号", "分组", "类型", "正文", "判断依据", "素材来源", "备注"]
    missing = [c for c in need if c not in (rows[0].keys() if rows else [])]
    if missing:
        raise SystemExit(f"表头缺列：{missing}（应为 {need}）")

    fraud = [r for r in rows if (r["分组"] or "").strip() == "诈骗"]
    normal = [r for r in rows if (r["分组"] or "").strip() == "正常"]
    other = [r for r in rows if (r["分组"] or "").strip() not in ("诈骗", "正常")]
    if other:
        errs.append(f"分组只能填「诈骗」或「正常」，有 {len(other)} 行不是："
                    + ", ".join(r["编号"] for r in other[:5]))
    if not a.allow_partial:
        if len(fraud) != 50:
            errs.append(f"A 组应为 50 条，实际 {len(fraud)} 条")
        if len(normal) != 50:
            errs.append(f"B 组应为 50 条，实际 {len(normal)} 条")

    for r in rows:
        code = (r["编号"] or "").strip()
        grp = (r["分组"] or "").strip()
        if grp == "诈骗" and not code.startswith("A"):
            errs.append(f"{code}：诈骗组编号应以 A 开头")
        if grp == "正常" and not code.startswith("B"):
            errs.append(f"{code}：正常组编号应以 B 开头")

    for prefix, group in (("A", fraud), ("B", normal)):
        codes = sorted((r["编号"] or "").strip() for r in group)
        want = [f"{prefix}{i:02d}" for i in range(1, len(group) + 1)]
        if codes and codes != want:
            dup = [c for c in set(codes) if codes.count(c) > 1]
            if dup:
                errs.append(f"{prefix} 组编号重复：{sorted(set(dup))[:5]}")
            else:
                errs.append(f"{prefix} 组编号不连续，期望 {want[0]}~{want[-1]}，实际 {codes[0]}~{codes[-1]}")

    # ---- 逐条 ----
    filled = 0
    for r in rows:
        code = (r["编号"] or "").strip()
        grp = (r["分组"] or "").strip()
        body = (r["正文"] or "").strip()
        why = (r["判断依据"] or "").strip()
        src = (r["素材来源"] or "").strip()

        if not body:
            if not a.allow_partial:
                errs.append(f"{code}：正文空")
            continue
        filled += 1

        n = len(body)
        if n < 10 or n > 80:
            errs.append(f"{code}：正文 {n} 字，超出 10~80 的硬范围（建议 15~60）")
        elif n < 15 or n > 60:
            warns.append(f"{code}：正文 {n} 字，建议 15~60")

        if '"' in body:
            errs.append(f'{code}：正文含英文双引号，CSV 会解析错，改用「」')
        if '"' in why:
            errs.append(f"{code}：判断依据含英文双引号，改用「」")

        if not why:
            errs.append(f"{code}：判断依据空（写一句为什么这是诈骗/为什么这合法）")
        if not src:
            warns.append(f"{code}：素材来源空（自己手机 / 12321 / 通报 / 群转发 / 自编）")

        hit = [w for w in A_BAN if w in body]
        if grp == "诈骗":
            if hit:
                errs.append(f"{code}：A 组出现索款/取密词 {hit}，这是 B 组或索款类的题")
        elif grp == "正常":
            if not any(w in body for w in B_HIGH):
                errs.append(f"{code}：B 组没有任何高危词，测不出误报")
            if not re.search(r"\d", body) and not any(
                    k in body for k in ("无需", "请勿", "可致电", "如有疑问", "到店", "亲自", "本人", "客服电话", "官网")):
                warns.append(f"{code}：B 组看不出「为什么这件事合法」，建议补尾号/官方号码/无需回复这类线索")

    # ---- 重复 ----
    by_group = {"诈骗": fraud, "正常": normal}
    for gname, group in by_group.items():
        seen = {}
        grams = []
        flagged = set()          # 一行只报一次，避免模板化时刷出上千条告警
        for r in group:
            b = norm(r["正文"])
            if not b:
                continue
            if b in seen:
                errs.append(f"{r['编号']} 与 {seen[b]} 正文完全相同（{gname}组）")
            else:
                seen[b] = r["编号"]
            g = bigrams(b)
            if r["编号"] not in flagged:
                best = max(((jaccard(g, g2), c2) for c2, g2 in grams), default=(0.0, None))
                if best[0] > 0.75:
                    warns.append(f"{r['编号']} 与 {best[1]} 字面相似度 {best[0]:.2f}，疑似同模板换词（{gname}组）")
                    flagged.add(r["编号"])
            grams.append((r["编号"], g))

    # ---- 与训练集撞车 ----
    if a.train:
        if not os.path.exists(a.train):
            errs.append(f"--train 指向的文件不存在：{a.train}")
        else:
            train = set()
            for line in open(a.train, encoding="utf-8"):
                if not line.strip():
                    continue
                d = json.loads(line)
                for m in d.get("messages", []):
                    if m.get("role") == "user":
                        t = m.get("content", "")
                        # 训练集 prompt 带固定前后缀，去掉再比
                        t = t.split("请根据通话内容")[0]
                        train.add(norm(re.sub(r"^下面是一段电话通话的转写文字：", "", t.strip())))
                        break
            hitn = 0
            for r in rows:
                b = norm(r["正文"])
                if b and b in train:
                    hitn += 1
                    errs.append(f"{r['编号']}：正文与训练集某条完全相同（leak_check 会挂）")
            print(f"撞车检查：训练集 {len(train)} 条，命中 {hitn} 条")
    else:
        print("撞车检查：未提供 --train，跳过（正式跑前建议带上）")

    # ---- 汇总 ----
    print(f"已填 {filled}/{len(rows)} 条（诈骗 {len(fraud)} / 正常 {len(normal)}）")
    if warns:
        print(f"\n告警 {len(warns)} 条：")
        for w in warns[:30]:
            print("  ! " + w)
        if len(warns) > 30:
            print(f"  ... 另有 {len(warns) - 30} 条")
    if errs:
        print(f"\n错误 {len(errs)} 条：")
        for e in errs[:40]:
            print("  x " + e)
        if len(errs) > 40:
            print(f"  ... 另有 {len(errs) - 40} 条")
        print("\nFAIL")
        sys.exit(1)

    print("\nPASS")

    if a.emit_jsonl:
        out = []
        for r in rows:
            body = (r["正文"] or "").strip()
            if not body:
                continue
            out.append({"id": r["编号"].strip(), "text": body,
                        "label": "fraud" if r["分组"].strip() == "诈骗" else "normal"})
        with open(a.emit_jsonl, "w", encoding="utf-8") as f:
            for o in out:
                f.write(json.dumps(o, ensure_ascii=False) + "\n")
        print(f"已写出 {len(out)} 条 -> {a.emit_jsonl}")
        print("下一步：python3 tools/eval_judge.py --url http://127.0.0.1:8000 "
              f"--input {a.emit_jsonl} --out <结果路径> --workers 3")


if __name__ == "__main__":
    main()
