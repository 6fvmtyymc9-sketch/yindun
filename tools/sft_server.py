#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AntiFraud-SFT 推理服务
====================================================================
把 D:\\models\\AntiFraud-SFT（Qwen2-Audio 7B 反诈微调）摆成一个 HTTP 接口，
云端 `cloud/src/Sft.js` 按 `SFT_URL` 调它。链路：

    录音  ──►  ASR 转写 ──┬─► 规则 v3.4（离线、毫秒）
                          ├─► DeepSeek（读懂"换说法"）
                          └─► 本服务（直接听音频，专用模型第三票）

为什么单独起一个进程，不塞进 Node：
    模型 16 GB、要 PyTorch，和 Node 服务抢内存；而且它慢（CPU 上一次前向
    几十秒），必须能独立重启、独立限流，不能拖垮接口。

【它和另外两路的定位不同】
    规则和 DeepSeek 看的是"文字转写"，转写丢掉了语气、语速、背景音；
    这个模型听的是音频本身。所以它**不是** DeepSeek 的替代品，是第三票。

【跑不动的场景要说清楚】
    ECS 是 2 核 2 GB 无 GPU，装不下 16 GB 权重，所以线上默认不启用
    （SFT_URL 留空即回退规则 + DeepSeek）。本机 / 有 GPU 的机器起起来就能接。

用法：
    set PYTHONPATH=
    D:\\models\\.venv-sft\\Scripts\\python.exe tools\\sft_server.py --port 8099

    curl http://127.0.0.1:8099/health
    curl -X POST http://127.0.0.1:8099/risk -H "Content-Type: application/json" \
         -d "{\"audio_b64\":\"...\",\"mime\":\"audio/mpeg\"}"
"""

import argparse
import base64
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL_DIR = os.environ.get("ANTIFRAUD_DIR", r"D:\models\AntiFraud-SFT")

AUDIO_PROMPT = (
    "这是一段电话通话录音。请判断打电话的人是不是在实施诈骗，"
    "只回答一个字：是，或者否。不要解释。"
)
TEXT_PROMPT = (
    "下面是一段电话通话的转写文字。请判断打电话的人是不是在实施诈骗，"
    "只回答一个字：是，或者否。不要解释。\n\n通话：{text}"
)

# ---------------------------------------------------------------- 模型（懒加载，单例）
_lock = threading.Lock()          # 一次只允许一个前向，CPU 上并发只会更慢
_state = {"loaded": False, "loading": False, "error": None, "loadMs": 0, "runs": 0}
_model = None
_processor = None
_yes_no = {}


def _load():
    """加载权重。放在后台线程里跑，HTTP 服务先起来，/health 能报"正在加载"。"""
    global _model, _processor, _yes_no
    _state["loading"] = True
    t0 = time.time()
    try:
        import torch
        from transformers import AutoProcessor, Qwen2AudioForConditionalGeneration

        _processor = AutoProcessor.from_pretrained(MODEL_DIR)
        _model = Qwen2AudioForConditionalGeneration.from_pretrained(
            MODEL_DIR,
            torch_dtype=torch.bfloat16,
            low_cpu_mem_usage=True,
            device_map={"": "cpu"},
        )
        _model.eval()

        tok = _processor.tokenizer
        _yes_no = {
            "yes": tok.encode("是", add_special_tokens=False),
            "no": tok.encode("否", add_special_tokens=False),
        }
        _state["loaded"] = True
        _state["loadMs"] = int((time.time() - t0) * 1000)
        print(f"[SFT] 模型就绪，耗时 {_state['loadMs']} ms；是={_yes_no['yes']} 否={_yes_no['no']}", flush=True)
    except Exception as e:  # noqa: BLE001
        _state["error"] = f"{type(e).__name__}: {e}"
        print(f"[SFT] 加载失败：{_state['error']}", flush=True)
    finally:
        _state["loading"] = False


def _resample(x, sr, target=16000):
    """线性插值重采样。录音多是 8k/16k，对语音判定足够，省一个 librosa 依赖。"""
    import numpy as np
    if sr == target:
        return x.astype("float32")
    n = int(round(len(x) * target / sr))
    if n <= 1:
        return x.astype("float32")
    idx = np.linspace(0, len(x) - 1, n)
    i0 = np.floor(idx).astype("int64")
    i1 = np.minimum(i0 + 1, len(x) - 1)
    w = (idx - i0).astype("float32")
    return (x[i0] * (1 - w) + x[i1] * w).astype("float32")


def _decode_audio(raw, name=""):
    """字节 → 单声道 16k float32。mp3/m4a/wav 走 soundfile。"""
    import io
    import numpy as np
    import soundfile as sf

    data, sr = sf.read(io.BytesIO(raw), dtype="float64", always_2d=True)
    mono = data.mean(axis=1) if data.shape[1] > 1 else data[:, 0]
    return _resample(mono, sr), sr, len(mono) / float(sr)


def _probe(text, audio):
    """一次前向，只读最后一个位置的 logits，比较「是」「否」两个 token 的概率。"""
    import torch

    if audio is not None:
        conv = [{"role": "user", "content": [
            {"type": "audio", "audio_url": "local"},
            {"type": "text", "text": AUDIO_PROMPT},
        ]}]
        chat = _processor.apply_chat_template(conv, add_generation_prompt=True, tokenize=False)
        inputs = _processor(text=chat, audio=[audio], sampling_rate=16000,
                            return_tensors="pt", padding=True)
    else:
        conv = [{"role": "user", "content": TEXT_PROMPT.format(text=text)}]
        chat = _processor.apply_chat_template(conv, add_generation_prompt=True, tokenize=False)
        inputs = _processor(text=chat, return_tensors="pt", padding=True)
        inputs.pop("input_features", None)          # 纯文本，别带空的音频特征
        inputs.pop("feature_attention_mask", None)

    with torch.no_grad():
        out = _model(**inputs)

    logits = out.logits[0, -1, :].float()
    yes_id = _yes_no["yes"][0]
    no_id = _yes_no["no"][0]
    pair = torch.softmax(torch.stack([logits[yes_id], logits[no_id]]), dim=0)
    return float(pair[0])


def _risk(payload):
    """统一入口。返回 {p, verdict}。p 是「是诈骗」的概率。"""
    audio = None
    meta = {}
    if payload.get("audio_b64"):
        raw = base64.b64decode(payload["audio_b64"])
        if len(raw) > 12 * 1024 * 1024:
            raise ValueError("音频超过 12MB")
        audio, sr, dur = _decode_audio(raw, payload.get("name", ""))
        meta = {"mode": "audio", "srcRate": sr, "durSec": round(dur, 1)}
    elif payload.get("text"):
        meta = {"mode": "text", "chars": len(payload["text"])}
    else:
        raise ValueError("audio_b64 和 text 至少给一个")

    t0 = time.time()
    with _lock:
        p = _probe(payload.get("text", ""), audio)
    meta["ms"] = int((time.time() - t0) * 1000)
    _state["runs"] += 1
    meta["p"] = round(p, 4)
    meta["verdict"] = "fraud" if p >= 0.5 else "normal"
    return meta


class Handler(BaseHTTPRequestHandler):
    server_version = "YindunSFT/1.0"

    def log_message(self, fmt, *args):      # 少刷屏，只打关键行
        sys.stderr.write("[SFT] " + (fmt % args) + "\n")

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            self._json(200, {
                "ok": bool(_state["loaded"]),
                "model": "AntiFraud-SFT",
                "dir": MODEL_DIR,
                "loading": _state["loading"],
                "error": _state["error"],
                "loadMs": _state["loadMs"],
                "runs": _state["runs"],
            })
        else:
            self._json(404, {"ok": False, "msg": "只有 /health 和 /risk"})

    def do_POST(self):
        if not self.path.startswith("/risk"):
            self._json(404, {"ok": False, "msg": "只有 /health 和 /risk"})
            return
        if not _state["loaded"]:
            self._json(503, {
                "ok": False,
                "msg": "模型还没加载好" if not _state["error"] else "模型加载失败：" + _state["error"],
            })
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0 or n > 20 * 1024 * 1024:
                raise ValueError("请求体大小不对")
            payload = json.loads(self.rfile.read(n).decode("utf-8"))
            out = _risk(payload)
            print(f"[SFT] {out}", flush=True)
            self._json(200, dict(ok=True, **out))
        except Exception as e:  # noqa: BLE001
            print(f"[SFT] 失败：{type(e).__name__}: {e}", flush=True)
            self._json(400, {"ok": False, "msg": f"{type(e).__name__}: {e}"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=int(os.environ.get("SFT_PORT", "8099")))
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--no-load", action="store_true", help="只起接口不加载模型（调通路用）")
    a = ap.parse_args()

    if not a.no_load:
        threading.Thread(target=_load, daemon=True).start()

    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f"[SFT] 监听 http://{a.host}:{a.port}  （模型在后台加载，先 /health 探）", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
