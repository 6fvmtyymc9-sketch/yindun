#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按清单跑音频评测（TAF-200 这类"文件名里没有中文标签"的评测集用）。

与 eval_audio.py 的区别：
  1. 用 --index 指定清单（{"file","label"} 每行一个），label 取自清单，
     不再靠文件名里的"正常/诈骗"猜——TAF 的文件名是 NEG-*__tts_*.mp3。
  2. 只跑清单里的文件，不遍历整个目录（taf_audio 有 7200 个）。
  3. mime 按扩展名给，不再硬编码 audio/wav。

输出字段与 eval_audio.py 对齐，两个口径（模型结论 / p>=0.85）都报。

用法：
  python3 tools/eval_audio_index.py --url http://127.0.0.1:8000 \
      --dir ~/taf/taf_audio --index ~/taf200/taf200_index.jsonl \
      --out ~/taf200/base_taf200_audio.jsonl --workers 3
"""
import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

MIME = {".wav": "audio/wav", ".mp3": "audio/mpeg", ".flac": "audio/flac", ".ogg": "audio/ogg"}


def post(url, payload, timeout=900):
    req = urllib.request.Request(
        url.rstrip("/") + "/judge",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def one(url, base_dir, row):
    name = row["file"]
    path = os.path.join(base_dir, name)
    ext = os.path.splitext(name)[1].lower()
    try:
        raw = open(path, "rb").read()
    except Exception as e:  # noqa: BLE001
        return {"file": name, "label": row["label"], "err": f"读不到文件: {e}",
                "is_fraud": None, "p": None}
    if len(raw) > 40 * 1024 * 1024:
        return {"file": name, "label": row["label"], "err": "音频超过 40MB",
                "is_fraud": None, "p": None}
    payload = {
        "audio_b64": base64.b64encode(raw).decode("ascii"),
        "name": name,
        "mime": MIME.get(ext, "audio/mpeg"),
    }
    t0 = time.time()
    try:
        res = post(url, payload)
    except Exception as e:  # noqa: BLE001
        return {"file": name, "label": row["label"], "err": f"{type(e).__name__}: {e}",
                "is_fraud": None, "p": None}
    out = {"file": name, "label": row["label"], "ms": int((time.time() - t0) * 1000)}
    for k in ("is_fraud", "p", "confidence", "reason", "verdict", "skipped",
              "mode", "durSec", "voicedSec"):
        out[k] = res.get(k)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--dir", required=True, help="音频所在目录")
    ap.add_argument("--index", required=True, help="清单 jsonl，每行 {file,label}")
    ap.add_argument("--out", required=True)
    ap.add_argument("--workers", type=int, default=3)
    a = ap.parse_args()

    rows = [json.loads(l) for l in open(os.path.expanduser(a.index), encoding="utf-8") if l.strip()]
    print(f"清单 {len(rows)} 条，并发 {a.workers}，目标 {a.url}", flush=True)
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        results = list(ex.map(lambda r: one(a.url, os.path.expanduser(a.dir), r), rows))

    out = os.path.expanduser(a.out)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fo:
        for r in results:
            fo.write(json.dumps(r, ensure_ascii=False) + "\n")

    for tag, key in (("模型结论", "verdict"), ("p>=0.85", "p85")):
        c = Counter()
        for r in results:
            lab = r["label"]
            if r.get("skipped") or r.get("err") or r.get("is_fraud") is None:
                c[(lab, "skipped")] += 1
                continue
            if key == "verdict":
                v = "fraud" if r["is_fraud"] else "normal"
            else:
                p = r.get("p")
                v = "unknown" if p is None else ("fraud" if p >= 0.85 else "normal")
            c[(lab, v)] += 1
        nf = sum(v for (l, _), v in c.items() if l == "fraud")
        nn = sum(v for (l, _), v in c.items() if l == "normal")
        print(f"[{tag}] 诈骗召回 {c[('fraud','fraud')]}/{nf}，"
              f"正常误报 {c[('normal','fraud')]}/{nn}，"
              f"未出结论 {c[('normal','skipped')] + c[('fraud','skipped')]}", flush=True)

    ms = [r["ms"] for r in results if r.get("ms")]
    if ms:
        ms.sort()
        print(f"单条耗时 中位 {ms[len(ms)//2]/1000:.1f}s  P90 {ms[int(len(ms)*0.9)]/1000:.1f}s  "
              f"最长 {ms[-1]/1000:.1f}s", flush=True)
    print(f"总耗时 {int(time.time()-t0)} 秒，结果 {out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
