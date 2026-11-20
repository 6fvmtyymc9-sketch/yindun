#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建 AntiFraud-SFT 微调数据（ms-swift 格式）
====================================================================
产出 jsonl，一行一条，供 `swift sft` 直接吃：

    {"messages":[{"role":"user","content":"<prompt+文本>"},
                 {"role":"assistant","content":"是"|"否"}]}

【prompt 必须和推理端一致】
与 `tools/sft_server.py` 的 TEXT_PROMPT 逐字相同，否则训完推理会掉点。

【三类来源】
  正样本（是）：Fraud-R1 中文 —— 1070 条，DeepSeek-R1 生成的诈骗文本
  负样本（否）：MagicData 真人对话 —— 9 通，真人口语、含采集口令需清洗
  负样本（否）：内置 synthetic.json —— 147 条，DeepSeek 合成，**只够跑通链路**

【为什么弃用 hf_sms】
  它的 `text_zh` 列不是中文短信，是英文短信机翻的产物，实测样本长这样：
  "Ma 头 dey 肿胀 oh. 谢谢你做我的日子"
  拿它当负样本，模型学到的是"机翻腔=正常"，对真人通话毫无迁移价值。

【真实的负样本缺口（2026-11-19 盘点）】
  MagicData 9 通 + collect_normal.csv 1 条 = 10 条真实正常样本。
  其余全是合成或规则测试集。正式训练必须等真人录音补齐，
  合成数据只用来说明"链路能跑通"，不能用来报效果。

【为什么要清洗 MagicData】
原始 txt 是 `[起,止]\t说话人\t性别\t文本` 的转写，且每段开头有
"北京爱数智慧语音采集。"的采集口令、[ENS]/[SONANT] 等非语音标记。
不清掉，模型会学到"采集口令=正常"这种垃圾特征。

用法：
    python build_sft_data.py --out-dir ./data --n-pos 300 --n-neg 300
    python build_sft_data.py --out-dir ./data --recordings ./真人录音  # 录音到位后
"""

import argparse
import glob
import io
import json
import os
import random
import re
import sys

# 与 tools/sft_server.py 的 TEXT_PROMPT 保持一致，改一处必须改两处
TEXT_PROMPT = (
    "下面是一段电话通话的转写文字。请判断打电话的人是不是在实施诈骗，"
    "只回答一个字：是，或者否。不要解释。\n\n通话：{text}"
)

# MagicData 采集口令，出现在每段开头，必须剔除
COLLECT_PROMPT = "北京爱数智慧语音采集"
# [ENS] [SONANT] [NOISE] 之类的非语音标记
MARKUP_RE = re.compile(r"\[(?:ENS|SONANT|NOISE|SPEECH|LAUGH|SIL)[^\]]*\]")
# 转写行：[0.469,1.342]\tSPK002\t男\t文本
LINE_RE = re.compile(r"^\[([\d.]+),([\d.]+)\]\t(\S*)\t(\S*)\t(.*)$")


def clean(text):
    """去掉标记、合并空白、截断。训练用 600 字足够，长文本只会拖慢且丢注意力。"""
    text = MARKUP_RE.sub("", text)
    text = text.replace(COLLECT_PROMPT, "")
    text = re.sub(r"(?i)https?://\S+", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:600]


def load_fraudr1(root):
    """
    Fraud-R1 中文，实际只有 1070 条。

    ⚠️ 目录下 FP-base 和 FP-levelup 各有一个 Chinese.json，都是 1070 条，
    且 `generated text` 逐字相同（2026-11-19 实测交集 1070）。直接合并会变成
    2138 条副本 —— 训练集里同一条文本出现两次，等价于给它加权，
    评估时还会误以为样本翻倍。按文本去重。
    """
    out, seen = [], set()
    for fp in sorted(glob.glob(os.path.join(root, "FP-*-full", "FP-*-Chinese.json"))):
        try:
            data = json.load(io.open(fp, encoding="utf-8"))
        except Exception as e:
            print(f"  [跳过] {fp}: {e}", file=sys.stderr)
            continue
        for x in data:
            t = clean(x.get("generated text") or "")
            if len(t) >= 20 and t not in seen:
                seen.add(t)
                out.append(t)
    return out


def load_magicdata(root):
    """
    真人对话，共 9 通。

    目录里两种录法混在一起（2026-11-19 实测 30 个文件 / 14 个 wav / 9 组）：
      - `G000x_S000x_0_SPK002/003`  双路分开录，末秒几乎相同 → 归并成一通
      - `20200327_2P_lenovo_iphonexr_66902`  双人混录单文件 → 本身就是一通
    只认同一种写法，会把 9 通缩成 5 通，负样本白白少掉四成。

    归并后两路文本按时间交织，才是一段完整对话；不归并则同一通电话出现两遍。
    """
    groups = {}
    for fp in glob.glob(os.path.join(root, "**", "*.txt"), recursive=True):
        base = os.path.basename(fp)[:-4]           # G0001_S0001_0_SPK002
        m = re.match(r"^(.*)_SPK(\d+)$", base)
        if m:
            key, spk = m.group(1), m.group(2)      # 双路：按通话分组
        else:
            key, spk = base, "0"                   # 单路混录：自成一通
        lines = []
        try:
            for raw in io.open(fp, encoding="utf-8", errors="replace"):
                mm = LINE_RE.match(raw.rstrip("\n"))
                if not mm:
                    continue
                start, _end, _spk, _sex, txt = mm.groups()
                txt = clean(txt)
                if txt:
                    lines.append((float(start), spk, txt))
        except Exception as e:
            print(f"  [跳过] {fp}: {e}", file=sys.stderr)
            continue
        groups.setdefault(key, []).extend(lines)

    out = []
    for key, lines in groups.items():
        lines.sort(key=lambda x: (x[0], x[1]))
        joined = clean(" ".join(t for _, _, t in lines))
        if len(joined) >= 40:
            out.append(joined)
    return out


def load_builtin_normal(root):
    """
    内置合成正常样本：`cloud/test/synthetic.json` 里 expect=normal 的那批（147 条）。

    ⚠️ 这是 DeepSeek 生成的，**只能用来跑通链路、不能用来出效果结论**：
       用 DeepSeek 合成的数据训练，再和 DeepSeek 比准确率，是循环论证。

    ⚠️ 不要用 cases.json / holdout.json —— 那是规则调参和留出评估用的，
       拿进来训练等于把考卷当教材，后面所有数字都会虚高。
    """
    fp = os.path.join(root, "synthetic.json")
    if not os.path.isfile(fp):
        print(f"  [跳过] 没有 {fp}", file=sys.stderr)
        return []
    out = []
    for c in json.load(io.open(fp, encoding="utf-8")).get("cases", []):
        if c.get("expect") != "normal":
            continue
        t = clean(c.get("text") or "")
        if len(t) >= 12:
            out.append(t)
    return out


def load_cgodial_split(root):
    """
    老师 2026-11-20 上传的 CGoDial 政务热线难负样本（`~/data/cgodial/`）。

    只用 `train_neg.jsonl`：里面是 `cgodial-split.js` 从 real 对话按场景分层抽样得到的，
    且已经优先收进规则会误报的那批（isFp=true，是最难的负样本）。
    留出的 `heldout.jsonl` 不参与训练，专用于报「政务热线误报率」，这里绝不读。

    文本用的是 ASR 版（text_asr），和线上 SIS 转写同源；人工转写太干净，训了到线上会掉点。
    """
    fp = os.path.join(root, "train_neg.jsonl")
    if not os.path.isfile(fp):
        print(f"      [跳过] 没找到 {fp}", file=sys.stderr)
        return []
    out = []
    for raw in io.open(fp, encoding="utf-8"):
        raw = raw.strip()
        if not raw:
            continue
        try:
            x = json.loads(raw)
        except ValueError:
            continue
        t = clean(x.get("text") or "")
        if len(t) >= 40:
            out.append(t)
    return out


def build(rows, path):
    with io.open(path, "w", encoding="utf-8") as f:
        for text, label in rows:
            rec = {"messages": [
                {"role": "user", "content": TEXT_PROMPT.format(text=text)},
                {"role": "assistant", "content": label},
            ]}
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return len(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fraudr1", default=r"D:\华为ict\外部数据集\fraudr1")
    ap.add_argument("--magicdata", default=r"D:\华为ict\外部数据集\magicdata")
    ap.add_argument("--builtin", default=r"D:\华为ict\交付_银盾_GitHub\cloud\test",
                    help="内置合成集目录（含 synthetic.json）")
    ap.add_argument("--recordings", default="", help="真人录音目录（可选，内有 labels.json）")
    ap.add_argument("--cgodial-split", dest="cgodial_split", default="",
                    help="老师 CGoDial 切分目录（含 train_neg.jsonl；不传则不使用）")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--n-pos", type=int, default=300)
    ap.add_argument("--n-neg", type=int, default=300)
    ap.add_argument("--val-ratio", type=float, default=0.15)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    random.seed(args.seed)
    os.makedirs(args.out_dir, exist_ok=True)

    print("[1/4] 读 Fraud-R1（诈骗）...")
    pos = load_fraudr1(args.fraudr1)
    print(f"      原始 {len(pos)} 条")

    print("[2/4] 读 MagicData（真人正常对话）...")
    neg_dlg = load_magicdata(args.magicdata)
    print(f"      归并后 {len(neg_dlg)} 通")

    if args.cgodial_split:
        cg = load_cgodial_split(args.cgodial_split)
        print(f"      老师 CGoDial 政务热线难负样本 {len(cg)} 条（real/ASR，已优先含规则误报难例）")
        neg_dlg = cg + neg_dlg

    print("[3/4] 读内置合成正常样本 ...")
    neg_sms = load_builtin_normal(args.builtin)
    print(f"      原始 {len(neg_sms)} 条（合成，仅够跑链路）")

    # 录音到位后的入口：labels.json 里每条有 text + label
    if args.recordings:
        lp = os.path.join(args.recordings, "labels.json")
        if os.path.isfile(lp):
            extra_p, extra_n = [], []
            for x in json.load(io.open(lp, encoding="utf-8")):
                t = clean(x.get("text") or "")
                if len(t) < 20:
                    continue
                (extra_p if x.get("label") == 1 else extra_n).append(t)
            print(f"[附加] 真人录音 正 {len(extra_p)} / 负 {len(extra_n)}")
            pos = extra_p + pos
            neg_dlg = extra_n + neg_dlg

    random.shuffle(pos)
    pos = pos[: args.n_pos]

    # 负样本优先用真人对话：够了就全用真人（含 CGoDial 难例），不够再拿合成补
    n_from_dlg = min(len(neg_dlg), args.n_neg)
    n_from_sms = min(len(neg_sms), args.n_neg - n_from_dlg)
    random.shuffle(neg_dlg)
    random.shuffle(neg_sms)
    neg = [(t, "否") for t in neg_dlg[:n_from_dlg]] + [(t, "否") for t in neg_sms[:n_from_sms]]
    if len(neg) < args.n_neg:
        print(f"      ⚠️ 负样本只有 {len(neg)} 条（目标 {args.n_neg}），比例会失衡",
              file=sys.stderr)

    rows = [(t, "是") for t in pos] + neg
    random.shuffle(rows)

    n_val = max(1, int(len(rows) * args.val_ratio))
    val, train = rows[:n_val], rows[n_val:]

    print("[4/4] 写出 ...")
    a = build(train, os.path.join(args.out_dir, "train.jsonl"))
    b = build(val, os.path.join(args.out_dir, "val.jsonl"))
    npos = sum(1 for _, l in train if l == "是")
    print(f"      train {a} 条（是 {npos} / 否 {a - npos}）")
    print(f"      val   {b} 条")
    print(f"      输出目录 {os.path.abspath(args.out_dir)}")


if __name__ == "__main__":
    main()
