#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 MagicData「中文会话会议」子集的 4 段长录音，切成评估集可用的正常样本。

为什么用这个子集：它是本机唯一 8 kHz 单声道的真实远程通话录音（电话带宽），
且自带逐句 [起,止] 时间戳转写，切段时不必再跑 ASR。

切 45 秒而不是 60 秒：云端 Asr.js 的 SIS 一句话识别单次上限约 55 秒，
45 秒留足余量，不会触发切段逻辑。

用法：
  PYTHONPATH= python tools/build_normal_eval.py --dry       # 只报告，不写文件
  PYTHONPATH= python tools/build_normal_eval.py --n 15      # 实际切 15 段
"""
import argparse
import json
import os
import re
import wave
from glob import glob

SRC = r"D:\华为ict\外部数据集\magicdata\meeting\A CHINESE CONVERSATIONAL MEETING SPEECH CORPUS"
DST = r"D:\华为ict\交付_银盾_GitHub\cloud\test\calls_eval\audio"
LABELS = r"D:\华为ict\交付_银盾_GitHub\cloud\test\calls_eval\labels.json"

LINE = re.compile(r"^\[(\d+\.\d+),(\d+\.\d+)\]\t(\S+)\t([^\t]*)\t(.*)$")
SEG_SEC = 45.0
SKIP_HEAD = 30.0          # 开头 30 秒是「北京爱数智慧语音采集」口播，跳过
MIN_SPEECH_RATIO = 0.35   # 窗口内说话时长占比下限，太静音的窗口不要
MIN_SWITCH = 4            # 窗口内至少几次说话人切换，保证是双方在聊
MARK = re.compile(r"\[[^\]]*\]")   # 清掉 [SONANT] / [ENS] / [*] 这类标注


def parse_turns(txt_path):
    turns = []
    with open(txt_path, encoding="utf-8") as f:
        for raw in f:
            m = LINE.match(raw.rstrip("\n"))
            if not m:
                continue
            a, b, spk, meta, text = m.groups()
            text = text.strip()
            if not text or text == "[ENS]":
                continue
            turns.append({"a": float(a), "b": float(b), "spk": spk, "meta": meta, "text": text})
    return turns


def speech_ratio(turns, t0, t1):
    """窗口内「有话音」的时长占比，重叠部分只算一次。"""
    iv = [(max(a["a"], t0), min(a["b"], t1)) for a in turns if a["b"] > t0 and a["a"] < t1]
    iv = sorted((x, y) for x, y in iv if y > x)
    total, cur_s, cur_e = 0.0, None, None
    for s, e in iv:
        if cur_s is None:
            cur_s, cur_e = s, e
        elif s <= cur_e:
            cur_e = max(cur_e, e)
        else:
            total += cur_e - cur_s
            cur_s, cur_e = s, e
    if cur_s is not None:
        total += cur_e - cur_s
    return total / max(t1 - t0, 1e-6)


def switches(turns, t0, t1):
    """窗口内说话人切换次数。真实电话是双方交替，单人独白不算像样样本。"""
    seq = [a["spk"] for a in turns if a["b"] > t0 and a["a"] < t1]
    return sum(1 for i in range(1, len(seq)) if seq[i] != seq[i - 1])


def align_start(turns, t):
    """把窗口起点挪到之后最近一句的开头，避免从半句话切开。"""
    for a in turns:
        if a["a"] >= t - 1.0:
            return max(0.0, a["a"] - 0.2)
    return t


def pick_windows(turns, dur, need):
    """挑说话密、且双方来回说的窗口，同一个文件内不重叠。"""
    cands = []
    t0 = SKIP_HEAD
    while t0 + SEG_SEC <= dur:
        r = speech_ratio(turns, t0, t0 + SEG_SEC)
        sw = switches(turns, t0, t0 + SEG_SEC)
        if r >= MIN_SPEECH_RATIO and sw >= MIN_SWITCH:
            cands.append((r * 0.5 + min(sw / 10.0, 1.0) * 0.5, t0, sw, r))
        t0 += 5.0
    cands.sort(reverse=True)
    chosen = []
    for _, t0, sw, r in cands:
        if all(abs(t0 - c["t0"]) >= SEG_SEC for c in chosen):
            st = align_start(turns, t0)
            chosen.append({"t0": st, "sw": sw, "ratio": r})
        if len(chosen) >= need:
            break
    return sorted(chosen, key=lambda x: x["t0"])


def text_of(turns, t0, t1):
    parts = []
    for a in turns:
        ov = min(a["b"], t1) - max(a["a"], t0)
        if ov <= 0:
            continue
        # 只用大部分落在窗口内的句子，避免把半句带进来
        if ov / max(a["b"] - a["a"], 1e-6) < 0.5:
            continue
        parts.append(MARK.sub("", a["text"]))
    return "".join(parts)


def cut(src, t0, t1, out):
    with wave.open(src, "rb") as w:
        assert w.getnchannels() == 1 and w.getframerate() == 8000, \
            f"预期 8k 单声道，实际 {w.getframerate()}Hz {w.getnchannels()}ch"
        w.setpos(int(t0 * 8000))
        frames = w.readframes(int((t1 - t0) * 8000))
        params = w.getparams()
    with wave.open(out, "wb") as o:
        o.setnchannels(1)
        o.setsampwidth(params.sampwidth)
        o.setframerate(8000)
        o.writeframes(frames)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=15)
    ap.add_argument("--dry", action="store_true")
    args = ap.parse_args()

    wavs = sorted(glob(os.path.join(SRC, "*.wav")))
    if not wavs:
        raise SystemExit(f"没找到音频：{SRC}")

    per = max(1, args.n // len(wavs))
    plan = []
    for w in wavs:
        base = os.path.splitext(os.path.basename(w))[0]
        txt = os.path.join(SRC, base + ".txt")
        turns = parse_turns(txt)
        with wave.open(w, "rb") as f:
            dur = f.getnframes() / f.getframerate()
            sr, ch = f.getframerate(), f.getnchannels()
        print(f"\n{base}\n  {sr}Hz {ch}ch {dur/60:.1f}分钟  {len(turns)} 个语音段")
        for p in pick_windows(turns, dur, per):
            t0, t1 = p["t0"], p["t0"] + SEG_SEC
            plan.append({"src": w, "base": base, "t0": t0, "t1": t1,
                         "ratio": p["ratio"], "sw": p["sw"], "text": text_of(turns, t0, t1)})
            print(f"  窗 {t0:7.1f}-{t1:7.1f}s  话音 {p['ratio']*100:4.1f}%  切换 {p['sw']:2d} 次  {len(text_of(turns,t0,t1))} 字")

    print(f"\n合计 {len(plan)} 段，每段 {SEG_SEC:.0f} 秒")
    if args.dry:
        print("（--dry，未写文件）")
        return

    os.makedirs(DST, exist_ok=True)
    items = []
    for i, p in enumerate(plan, 1):
        name = f"eval_正常_同事通话_{i:02d}.wav"
        out = os.path.join(DST, name)
        cut(p["src"], p["t0"], p["t1"], out)
        with open(os.path.splitext(out)[0] + ".txt", "w", encoding="utf-8") as f:
            f.write(p["text"] + "\n")
        items.append({
            "file": f"audio/{name}",
            "label": "正常",
            "scenario": "同事通话",
            "speaker": "公开语料",
            "date": "2026-11-20",
            "duration_sec": int(SEG_SEC),
            "transcript": f"audio/{os.path.splitext(name)[0]}.txt",
            "source": "magicdata meeting 子集（8kHz 单声道真实远程通话）",
            "note": "非自录；内容为同事讨论工作事项，非陌生人来电",
        })
        print(f"  ✔ {name}  {os.path.getsize(out)//1024} KB")

    if os.path.exists(LABELS):
        with open(LABELS, encoding="utf-8") as f:
            lab = json.load(f)
    else:
        lab = {"frozen": False, "frozenAt": None, "frozenCommit": None, "items": []}
    lab["items"] = [x for x in lab.get("items", []) if x.get("speaker") != "公开语料"] + items
    lab["frozen"] = False
    lab["_进度"] = f"正常 {sum(1 for x in lab['items'] if x['label']=='正常')} 条 / 诈骗 {sum(1 for x in lab['items'] if x['label']=='诈骗')} 条"
    with open(LABELS, "w", encoding="utf-8") as f:
        json.dump(lab, f, ensure_ascii=False, indent=2)
    print(f"\n已写入 {LABELS}：{lab['_进度']}")


if __name__ == "__main__":
    main()
