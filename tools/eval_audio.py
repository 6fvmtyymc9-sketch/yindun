#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
30 通真人录音 → /judge 音频模式评测
====================================================================
与 eval_judge.py 的分工：那个跑文字，这个跑音频（base64 上传 wav）。
输出字段与基线 out-sft-judge.jsonl 对齐，便于直接对比。

用法：
  python3 eval_audio.py --url http://127.0.0.1:8001 --dir audio --out eval/x.jsonl --workers 3
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


def post(url, payload, timeout=900):
    req = urllib.request.Request(
        url.rstrip("/") + "/judge",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def label_of(name):
    if "正常" in name:
        return "normal"
    if "诈骗" in name:
        return "fraud"
    return "unknown"


def one(args, path):
    raw = open(path, "rb").read()
    if len(raw) > 12 * 1024 * 1024:
        return {"file": os.path.basename(path), "label": label_of(os.path.basename(path)),
                "err": "音频超过 12MB，未送模型", "is_fraud": None, "p": None}
    payload = {
        "audio_b64": base64.b64encode(raw).decode("ascii"),
        "name": os.path.basename(path),
        "mime": "audio/wav",
    }
    t0 = time.time()
    try:
        res = post(args.url, payload)
    except Exception as e:  # noqa: BLE001
        return {"file": os.path.basename(path), "label": label_of(os.path.basename(path)),
                "err": f"{type(e).__name__}: {e}", "is_fraud": None, "p": None}
    out = {"file": os.path.basename(path), "label": label_of(os.path.basename(path)),
           "ms": int((time.time() - t0) * 1000)}
    for k in ("is_fraud", "p", "confidence", "reason", "verdict", "skipped", "mode",
              "durSec", "voicedSec"):
        out[k] = res.get(k)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--workers", type=int, default=3)
    a = ap.parse_args()

    files = sorted(
        os.path.join(a.dir, f) for f in os.listdir(os.path.expanduser(a.dir))
        if f.lower().endswith((".wav", ".mp3", ".flac", ".ogg"))
    )
    if not files:
        print("目录里没有音频", file=sys.stderr)
        return 1

    print(f"共 {len(files)} 条，并发 {a.workers}，目标 {a.url}", flush=True)
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        results = list(ex.map(lambda p: one(a, p), files))

    os.makedirs(os.path.dirname(os.path.abspath(os.path.expanduser(a.out))), exist_ok=True)
    with open(os.path.expanduser(a.out), "w", encoding="utf-8") as fo:
        for r in results:
            fo.write(json.dumps(r, ensure_ascii=False) + "\n")

    # ---- 两个口径，与老师要求一致：模型结论 + p>=0.85 ----
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
        print(f"[{tag}] 诈骗判对 {c[('fraud','fraud')]}/{nf}，"
              f"正常误报 {c[('normal','fraud')]}/{nn}，"
              f"未出结论 {c[('normal','skipped')] + c[('fraud','skipped')]}", flush=True)

    print(f"总耗时 {int(time.time()-t0)} 秒，结果 {a.out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
