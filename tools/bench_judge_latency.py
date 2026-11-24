#!/usr/bin/env python3
"""测 SFT 服务 /judge 的单次耗时，用来定线上 SFT_WAIT_MS。

为什么要单独测：原始模型要两轮对话（判场景 → 写分析 → 输出 JSON），
文档写的 5~60 秒是跨度，不能直接拿来当等待上限。SFT_WAIT_MS 设小了
等于没接（每次都超时降级两票），设大了会让 App 端等到无响应。

用法（在学校容器上跑，服务监听本机）：
    python3 tools/bench_judge_latency.py --url http://127.0.0.1:8000 \
        --audio demo/calls/*.mp3

    # 只测文字
    python3 tools/bench_judge_latency.py --text-file some.txt

输出每条的耗时、模型结论，最后给 min / 中位 / P90 / max，并据此建议 SFT_WAIT_MS。
"""
import argparse
import base64
import glob
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request


def post_judge(url, payload, timeout):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url.rstrip("/") + "/judge",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return time.time() - t0, data, None
    except urllib.error.URLError as e:
        return time.time() - t0, None, f"URLError: {e}"
    except Exception as e:  # noqa: BLE001
        return time.time() - t0, None, f"{type(e).__name__}: {e}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8000")
    ap.add_argument("--timeout", type=float, default=180.0, help="单条 HTTP 超时（秒）")
    ap.add_argument("--audio", nargs="*", default=[], help="音频文件，支持通配")
    ap.add_argument("--text-file", nargs="*", default=[], help="纯文本文件（每题一个文件）")
    ap.add_argument("--text", default=None, help="直接给一句话测")
    args = ap.parse_args()

    # 通配在这里展开，避免依赖 shell
    audio = []
    for pat in args.audio:
        audio.extend(sorted(glob.glob(pat)) or [pat])

    tasks = []
    for f in audio:
        tasks.append(("audio", f))
    for f in args.text_file:
        tasks.append(("text", f))

    if args.text:
        tasks.append(("text-inline", args.text))

    if not tasks:
        ap.error("至少给 --audio / --text-file / --text 之一")

    rows = []
    print(f"{'文件':<42} {'模式':<6} {'耗时':>8}  {'结论':<8} {'p':>7} {'conf':>7}  备注")
    print("-" * 110)
    for kind, src in tasks:
        if kind == "audio":
            b64 = base64.b64encode(open(src, "rb").read()).decode("ascii")
            payload = {"audio_b64": b64}
            name = os.path.basename(src)
        elif kind == "text":
            payload = {"text": open(src, encoding="utf-8").read().strip()}
            name = os.path.basename(src)
        else:
            payload = {"text": src}
            name = src[:30] + "…"

        el, data, err = post_judge(args.url, payload, args.timeout)
        if err:
            print(f"{name:<42} {kind:<6} {el:>7.1f}s  --        --       --      {err}")
            rows.append((name, el, None))
            continue

        verdict = "诈骗" if data.get("is_fraud") else ("跳过" if data.get("skipped") else "正常")
        p = data.get("p")
        conf = data.get("confidence")
        note = ""
        if data.get("skipped"):
            note = f"skipped: {data.get('skippedReason') or data.get('msg') or ''}"
        print(
            f"{name:<42} {kind:<6} {el:>7.1f}s  {verdict:<8} "
            f"{(f'{p:.3f}' if isinstance(p, (int, float)) else '--'):>7} "
            f"{(f'{conf:.3f}' if isinstance(conf, (int, float)) else '--'):>7}  {note}"
        )
        rows.append((name, el, data))

    ok = [(n, e) for n, e, d in rows if d is not None]
    if not ok:
        print("\n全部失败，无法给出建议。")
        return 1

    times = [e for _, e in ok]
    times_sorted = sorted(times)
    p90 = times_sorted[min(len(times_sorted) - 1, int(round(0.9 * (len(times_sorted) - 1))))]
    med = statistics.median(times)

    print("\n" + "=" * 60)
    print(f"样本 {len(times)} 条   最快 {min(times):.1f}s   中位 {med:.1f}s   P90 {p90:.1f}s   最慢 {max(times):.1f}s")
    # 建议值：P90 上浮 30%，向上取整到秒，再乘 1000
    suggestion = int((p90 * 1.3 + 0.999) * 1000)
    print(f"建议 SFT_WAIT_MS = {suggestion}   （P90 × 1.3，向上取整）")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
