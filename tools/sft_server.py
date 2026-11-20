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

【两个判定接口】
    POST /judge  推荐。按模型训练时的原生两轮问法：先判通话场景，再写分析、输出
                 {"reason","confidence","is_fraud"}。要生成几百字，GPU 上 5~60 秒。
    POST /risk   旧接口，只比较第一个 token「是」「否」的概率，0.15 秒。
                 ⚠️ 模型从没被训练成「只答一个字」，这个 p 和输入内容关系很弱
                 （静音都能得 0.62），2026-11-20 实测证实，仅为兼容保留，不要用来判定。

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

DEV = os.environ.get("SFT_DEVICE", "cpu")
MODEL_DIR = os.environ.get("ANTIFRAUD_DIR", r"D:\models\AntiFraud-SFT")

AUDIO_PROMPT = (
    "这是一段电话通话录音。请判断打电话的人是不是在实施诈骗，"
    "只回答一个字：是，或者否。不要解释。"
)
TEXT_PROMPT = (
    "下面是一段电话通话的转写文字。请判断打电话的人是不是在实施诈骗，"
    "只回答一个字：是，或者否。不要解释。\n\n通话：{text}"
)

# 原生问法：与 TeleAntiFraud 训练数据 / 官方评测一致（见其仓库 example/、evaluation/）
SCENES = "订餐服务、咨询客服、预约服务、交通咨询、日常购物、打车服务、外卖服务"
NATIVE_Q1 = (
    "请根据你听到的音频内容，详细分析通话场景属于以下哪种类型：" + SCENES + "\n"
    "输出格式：\n{\n  \"scene\": \"<scene>\",\n  \"reason\": \"<reason>\",\n  \"confidence\": <confidence_level>\n}"
)
NATIVE_Q1_TEXT = (
    "下面是一段电话通话的转写文字：\n{text}\n\n"
    "请根据通话内容，详细分析通话场景属于以下哪种类型：" + SCENES + "\n"
    "输出格式：\n{{\n  \"scene\": \"<scene>\",\n  \"reason\": \"<reason>\",\n  \"confidence\": <confidence_level>\n}}"
)
NATIVE_Q2 = (
    "任务描述：\n你是一个专业的音频大模型，能够直接分析音频内容并判断其是否涉及诈骗。请根据以下信息，输出你的判断：\n"
    "第一轮分析的通话场景。\n音频内容。\n输出格式：\n"
    "{\n  \"reason\": \"<reason_for_judgment>\",\n  \"confidence\": <confidence_level>,\n  \"is_fraud\": <true/false>\n}"
)
MAX_NEW = int(os.environ.get("SFT_MAX_NEW", "1536"))   # 每轮最多生成多少 token
MIN_VOICED_SEC = 1.0      # 有声段不足 1 秒不送模型（静音会被它编出一段对话判成诈骗）
MIN_TEXT_CHARS = 15       # 转写不足 15 字同理

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
            device_map={"": DEV},
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
        # transformers <4.50 的参数名是 audios，新版改成 audio；传错名字会被静默忽略
        import inspect
        akey = "audios" if "audios" in inspect.signature(_processor.__call__).parameters else "audio"
        inputs = _processor(text=chat, sampling_rate=16000, return_tensors="pt",
                            padding=True, **{akey: [audio]})
    else:
        conv = [{"role": "user", "content": TEXT_PROMPT.format(text=text)}]
        chat = _processor.apply_chat_template(conv, add_generation_prompt=True, tokenize=False)
        # 纯文本直接走分词器：transformers <4.50 的处理器不给音频会报 len(None)
        inputs = _processor.tokenizer(chat, return_tensors="pt", padding=True)

    with torch.no_grad():
        out = _model(**{k: (v.to(DEV, _model.dtype) if v.is_floating_point() else v.to(DEV)) for k, v in inputs.items()})

    logits = out.logits[0, -1, :].float()
    yes_id = _yes_no["yes"][0]
    no_id = _yes_no["no"][0]
    pair = torch.softmax(torch.stack([logits[yes_id], logits[no_id]]), dim=0)
    return float(pair[0])


def _voiced_sec(audio, sr=16000):
    """粗略有声时长：30ms 一帧，RMS 超过 0.01 算有声。只用来挡静音、空录音。"""
    import numpy as np
    f = int(sr * 0.03)
    n = len(audio) // f
    if n == 0:
        return 0.0
    rms = np.sqrt((audio[: n * f].reshape(n, f) ** 2).mean(axis=1))
    return float((rms > 0.01).sum()) * 0.03


def _inputs(conv, audio, prefix=""):
    import inspect
    chat = _processor.apply_chat_template(conv, add_generation_prompt=True, tokenize=False) + prefix
    if audio is not None:
        akey = "audios" if "audios" in inspect.signature(_processor.__call__).parameters else "audio"
        inputs = _processor(text=chat, sampling_rate=16000, return_tensors="pt",
                            padding=True, **{akey: [audio]})
    else:
        inputs = _processor.tokenizer(chat, return_tensors="pt", padding=True)
    return {k: (v.to(DEV, _model.dtype) if v.is_floating_point() else v.to(DEV)) for k, v in inputs.items()}


def _generate(conv, audio, prefix="", max_new=None):
    """prefix 非空时，把它当作助手回答的开头接着写（用于逼出漏写的 JSON）。"""
    import torch
    inputs = _inputs(conv, audio, prefix)
    with torch.no_grad():
        out = _model.generate(**inputs, max_new_tokens=max_new or MAX_NEW, do_sample=False)
    new = out[0, inputs["input_ids"].shape[1]:]
    return _processor.tokenizer.decode(new, skip_special_tokens=True), int(new.shape[0])


def _last_json(text, key):
    """取文中最后一个含 key 的 JSON 对象。模型常先给 JSON 再写注释，或包在 ```json 里。"""
    import re
    dec = json.JSONDecoder()
    found = None
    for m in re.finditer(r"\{", text):
        try:
            obj, _ = dec.raw_decode(text[m.start():])
        except ValueError:
            continue
        if isinstance(obj, dict) and key in obj:
            found = obj
    return found


def _parse_verdict(text):
    """解析第二轮输出 → (is_fraud, confidence, reason, how)。how 说明是从哪解析出来的。"""
    import re
    obj = _last_json(text, "is_fraud")
    if obj is not None:
        v = obj.get("is_fraud")
        if isinstance(v, str):
            v = v.strip().lower() in ("true", "是", "yes", "1")
        try:
            conf = float(obj.get("confidence"))
        except (TypeError, ValueError):
            conf = None
        return bool(v), conf, str(obj.get("reason", "")), "json"
    # 写到上限没收尾：从思考文字里找「is_fraud 为 true/false」
    m = re.findall(r'is_fraud["\s]*(?:为|是|:|：|=)\s*(true|false)', text, re.I)
    if m:
        return m[-1].lower() == "true", None, "", "text"
    return None, None, "", "none"


def _judge(payload):
    """原生两轮判定。返回 {is_fraud, p, confidence, reason, scene, ...}。"""
    audio, text = None, (payload.get("text") or "").strip()
    meta = {}
    if payload.get("audio_b64"):
        raw = base64.b64decode(payload["audio_b64"])
        if len(raw) > 12 * 1024 * 1024:
            raise ValueError("音频超过 12MB")
        audio, sr, dur = _decode_audio(raw, payload.get("name", ""))
        audio = audio[: 16000 * 30]          # 编码器只看前 30 秒
        voiced = _voiced_sec(audio)
        meta = {"mode": "audio", "srcRate": sr, "durSec": round(dur, 1), "voicedSec": round(voiced, 1)}
        if voiced < MIN_VOICED_SEC:
            return dict(meta, skipped=True, is_fraud=None, p=None, verdict="unknown",
                        reason="有声部分不足 %.0f 秒，未送模型" % MIN_VOICED_SEC, ms=0)
    elif text:
        meta = {"mode": "text", "chars": len(text)}
        if len(text) < MIN_TEXT_CHARS:
            return dict(meta, skipped=True, is_fraud=None, p=None, verdict="unknown",
                        reason="转写不足 %d 字，未送模型" % MIN_TEXT_CHARS, ms=0)
        text = text[:6000]
    else:
        raise ValueError("audio_b64 和 text 至少给一个")

    t0 = time.time()
    with _lock:
        if audio is not None:
            c = [{"role": "user", "content": [{"type": "audio", "audio_url": "local"},
                                              {"type": "text", "text": NATIVE_Q1}]}]
        else:
            c = [{"role": "user", "content": NATIVE_Q1_TEXT.format(text=text)}]
        r1, n1 = _generate(c, audio)
        q2 = NATIVE_Q2 if audio is not None else NATIVE_Q2.replace("音频内容", "通话内容")
        c = c + [{"role": "assistant", "content": r1},
                 {"role": "user", "content": [{"type": "text", "text": q2}] if audio is not None else q2}]
        r2, n2 = _generate(c, audio)
        if _last_json(r2, "is_fraud") is None:
            # 实测约 1/9 的样本写完分析就停了、没给 JSON：把分析当开头，让它把 JSON 补上
            pre = r2.rstrip() + '\n\n{\n  "reason": "'
            tail, n3 = _generate(c, audio, prefix=pre, max_new=256)
            r2 = pre + tail
            n2 += n3
    meta["ms"] = int((time.time() - t0) * 1000)
    _state["runs"] += 1

    scene = (_last_json(r1, "scene") or {}).get("scene")
    is_fraud, conf, reason, how = _parse_verdict(r2)
    if is_fraud is None:
        p = None
    elif conf is None or not (0 <= conf <= 1):
        p = 1.0 if is_fraud else 0.0
    else:
        p = conf if is_fraud else 1 - conf
    meta.update({
        "is_fraud": is_fraud,
        "verdict": "unknown" if is_fraud is None else ("fraud" if is_fraud else "normal"),
        "p": None if p is None else round(p, 4),
        "confidence": conf,
        "reason": reason,
        "scene": scene,
        "parsedFrom": how,
        "tokens": n1 + n2,
    })
    if payload.get("debug"):
        meta["raw"] = {"scene": r1, "judge": r2}
    return meta


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
            self._json(404, {"ok": False, "msg": "只有 /health、/judge 和 /risk"})

    def do_POST(self):
        if not (self.path.startswith("/risk") or self.path.startswith("/judge")):
            self._json(404, {"ok": False, "msg": "只有 /health、/judge 和 /risk"})
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
            out = _judge(payload) if self.path.startswith("/judge") else _risk(payload)
            print(f"[SFT] {self.path} {({k: v for k, v in out.items() if k != 'raw'})}", flush=True)
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
