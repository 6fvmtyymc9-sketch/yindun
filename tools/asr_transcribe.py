#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TeleAntiFraud audio.zip 的录音 → 文字（faster-whisper large-v3，GPU）。

标签取自 index.jsonl（POS-* = fraud，NEG-* = normal），不用 sft.zip 里的 is_fraud。

输出每行：
    {"file", "label", "group", "text", "durSec", "nSeg", "ms"}
可断点续跑：已出现在 out 里的 file 会跳过。
"""
import argparse
import json
import os
import sys
import time

from faster_whisper import BatchedInferencePipeline, WhisperModel


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="/home/zkpk/taf/taf_audio")
    ap.add_argument("--index", default="")
    ap.add_argument("--out", default="/home/zkpk/taf/transcripts.jsonl")
    ap.add_argument("--cache", default="/home/zkpk/models/asr")
    ap.add_argument("--model", default="large-v3")
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()

    index = a.index or os.path.join(a.dir, "index.jsonl")
    rows = [json.loads(l) for l in open(index, encoding="utf-8") if l.strip()]
    if a.limit:
        rows = rows[: a.limit]

    done = set()
    if os.path.exists(a.out):
        for l in open(a.out, encoding="utf-8"):
            try:
                done.add(json.loads(l)["file"])
            except Exception:
                pass
    todo = [r for r in rows if r["file"] not in done]
    print(f"总计 {len(rows)}，已完成 {len(done)}，本轮 {len(todo)}", flush=True)

    model = WhisperModel(a.model, device="cuda", compute_type="float16", download_root=a.cache)
    pipe = BatchedInferencePipeline(model=model)

    t0 = time.time()
    fo = open(a.out, "a", encoding="utf-8")
    with fo:
        for i, r in enumerate(todo, 1):
            p = os.path.join(a.dir, r["file"])
            st = time.time()
            try:
                segs, info = pipe.transcribe(p, language="zh", batch_size=a.batch,
                                             vad_filter=True, beam_size=5)
                segs = list(segs)
                text = "".join(s.text for s in segs).strip()
                text = "".join(text.split())
                rec = {"file": r["file"], "label": r["label"], "group": r["group"],
                       "text": text, "durSec": round(info.duration, 1),
                       "voicedSec": round(sum(s.end - s.start for s in segs), 1),
                       "nSeg": len(segs), "ms": int((time.time() - st) * 1000)}
            except Exception as e:
                rec = {"file": r["file"], "label": r["label"], "group": r["group"],
                       "text": "", "err": str(e)[:200], "ms": int((time.time() - st) * 1000)}
            fo.write(json.dumps(rec, ensure_ascii=False) + "\n")
            fo.flush()
            if i % 20 == 0 or i == len(todo):
                el = time.time() - t0
                sp = sum(1 for _ in open(a.out, encoding="utf-8"))
                eta = el / i * (len(todo) - i) / 60
                print(f"[{i}/{len(todo)}] 已写 {sp} 条  {el/60:.1f} min  ETA {eta:.0f} min", flush=True)


if __name__ == "__main__":
    main()
