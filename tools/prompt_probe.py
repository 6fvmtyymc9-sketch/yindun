#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Prompt 形态对照 —— 判断「恒答是」是不是问法不匹配造成的
========================================================
发现（零推理成本，直接看 sft/train.jsonl）：

  训练时的输入是**两轮对话 + JSON 输出**：
    轮1  system「你是个有用的音频理解助手。」
         user   <audio> + 「判断通话场景属于以下哪种类型」（7 类闭集）
         assistant  ```json {"scene":"咨询客服","reason":"...","confidence":0.9}```
    轮2  user   「你是专业的音频大模型…判断其是否涉及诈骗」
         assistant  <think>…</think><answer>
                    ```json {"reason":"...","confidence":0.9,"is_fraud":true}```

  而 tools/sft_server.py 里问的是：
         「这是一段电话通话录音。请判断打电话的人是不是在实施诈骗，
           只回答一个字：是，或者否。不要解释。」

  **训练里从来没有「是/否」这个输出形式，也没有 system prompt、没有先做场景分类。**
  所以 _probe 比较「是」「否」两个 token 的 logits，问的是一个模型没被训练过的题型。

本脚本对同一批音频跑三种问法，看判别力是否随问法恢复：

  P1  现行单字问法        —— logits 读「是」vs「否」
  P2  训练同构（单轮）    —— 照抄轮2 的 JSON 格式，generate 后解析 is_fraud
  P3  训练同构（两轮）    —— 先场景分类，再诈骗判断（完全复刻训练流程）

判读：
  P1 全「是」而 P2/P3 能分开 POS 与 NEG  → 恒答是是**问法**问题，可直接修
  三者全「是」                            → **权重**问题，prompt 救不了

用法：
  set PYTHONPATH=
  D:\\models\\.venv-sft\\Scripts\\python.exe tools\\prompt_probe.py --sec 18
"""
import argparse
import glob
import io
import json
import os
import re
import sys
import time
import zipfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sft_server as S

AUDIO_ZIP = r"D:/下载/audio.zip"

SYS = "你是个有用的音频理解助手。"

# ---- 轮1：场景分类（照抄训练数据原文）----
SCENE_PROMPT = """<audio>
**任务描述：**

请根据你听到的音频内容，判断通话场景属于以下哪种类型：

- 订餐服务
- 咨询客服
- 预约服务
- 交通咨询
- 日常购物
- 打车服务
- 外卖服务

**输出格式：**

请按照以下格式输出你的判断结果：

```
{
  "scene": "<scene_type>",
  "reason": "<reason_for_judgment>",
  "confidence": <confidence_level>
}
```
"""

# ---- 轮2：诈骗判断（照抄训练数据原文）----
FRAUD_PROMPT = """
**任务描述：**

你是一个专业的音频大模型，能够详细分析音频内容并判断其是否涉及诈骗。请根据以下信息，输出你的判断：

1. 第一轮分析的通话场景。
2. 音频内容。

**输出格式：**

请按照以下格式输出你的判断结果：

<think>
...(详细分析过程)
</think>
<answer>
```
{
  "reason": "<reason_for_judgment>",
  "confidence": <confidence_level>,
  "is_fraud": <true/false>
}
```
</answer>
"""


def pick_audio(sec, per=1):
    """从 audio.zip 取 POS / NEG 各若干条，返回 [(tag, truth, mono, sr)]。"""
    import soundfile as sf
    z = zipfile.ZipFile(AUDIO_ZIP)
    names = [i.filename for i in z.infolist() if i.filename.endswith(".mp3")]
    out = []
    for pre, truth in (("POS-", "fraud"), ("NEG-", "normal")):
        cand = sorted(n for n in names if f"/{pre}" in n)
        # 均匀取 2 条，避开同类相邻
        k = max(1, per)
        picks = [cand[len(cand) * i // (k + 1)] for i in range(1, k + 1)] if k > 1 \
            else [cand[len(cand) // 2]]
        for k, n in enumerate(picks):
            d, sr = sf.read(io.BytesIO(z.read(n)), dtype="float32", always_2d=True)
            mono = d.mean(axis=1) if d.shape[1] > 1 else d[:, 0]
            mono = mono[:int(sec * sr)]
            out.append((f"{pre[:3]}-{k+1}", truth, mono, sr, n.split("/")[-1]))
    return out


def audio_16k(mono, sr):
    return S._resample(mono, sr, 16000)


def run_p1(audio):
    """现行单字问法：直接复用 sft_server._probe。"""
    return S._probe("", audio), "是/否"


def _chat(model_processor, msgs):
    return model_processor.apply_chat_template(msgs, add_generation_prompt=True, tokenize=False)


def _gen(audio, chat, max_new=160):
    """跑一次 generate，返回解码文本。"""
    import torch
    import inspect
    ak = "audios" if "audios" in inspect.signature(S._processor.__call__).parameters else "audio"
    inputs = S._processor(text=chat, sampling_rate=16000, return_tensors="pt",
                          padding=True, **{ak: [audio]})
    with torch.no_grad():
        out = S._model.generate(
            **{k: (v.to(S.DEV, S._model.dtype) if v.is_floating_point() else v.to(S.DEV))
               for k, v in inputs.items()},
            max_new_tokens=max_new, do_sample=False,
        )
    gen = out[0][inputs["input_ids"].shape[1]:]
    return S._processor.tokenizer.decode(gen, skip_special_tokens=True)


def parse_fraud(txt):
    """从生成文本里抠 is_fraud。抠不到返回 None。"""
    m = re.search(r'"is_fraud"\s*:\s*(true|false)', txt, re.I)
    if m:
        return m.group(1).lower() == "true"
    m = re.search(r'is_fraud\s*[：:]\s*(true|false)', txt, re.I)
    if m:
        return m.group(1).lower() == "true"
    # 兜底：看有没有裸的 true
    if re.search(r'\btrue\b', txt, re.I) and not re.search(r'\bfalse\b', txt, re.I):
        return True
    if re.search(r'\bfalse\b', txt, re.I):
        return False
    return None


def run_p2(audio, max_new=120):
    msgs = [
        {"role": "system", "content": SYS},
        {"role": "user", "content": [
            {"type": "audio", "audio_url": "local"},
            {"type": "text", "text": FRAUD_PROMPT},
        ]},
    ]
    txt = _gen(audio, _chat(S._processor, msgs), max_new)
    return parse_fraud(txt), txt


def run_p3(audio, max_new=200):
    """完整两轮：先场景分类，再用它的输出做第二轮输入。"""
    msgs = [
        {"role": "system", "content": SYS},
        {"role": "user", "content": [
            {"type": "audio", "audio_url": "local"},
            {"type": "text", "text": SCENE_PROMPT},
        ]},
    ]
    scene = _gen(audio, _chat(S._processor, msgs), 120)
    msgs.append({"role": "assistant", "content": scene})
    msgs.append({"role": "user", "content": FRAUD_PROMPT})
    # 第二轮不再重复给音频，但要保留轮1的音频 token —— 所以重拼含音频的完整串
    msgs[1]["content"] = [
        {"type": "audio", "audio_url": "local"},
        {"type": "text", "text": SCENE_PROMPT},
    ]
    txt = _gen(audio, _chat(S._processor, msgs), max_new)
    return parse_fraud(txt), scene, txt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sec", type=float, default=10.0)
    ap.add_argument("--out", default=r"D:/华为ict/外部数据集/prompt_probe.json")
    ap.add_argument("--per", type=int, default=1, help="每类取几条")
    ap.add_argument("--p3", action="store_true", help="额外跑完整两轮（慢一倍）")
    a = ap.parse_args()

    items = pick_audio(a.sec, a.per)
    print(f"[素材] {len(items)} 条，每条 {a.sec:.0f} 秒（全部来自 TeleAntiFraud 公开音频）")
    for t, truth, mono, sr, fn in items:
        print(f"  {t:7s} truth={truth:6s} {sr}Hz  {len(mono)/sr:.1f}s  {fn}")

    print("\n[模型] 加载中（CPU 约 2 分钟）…", flush=True)
    S._load()
    if S._state["error"]:
        print("[致命]", S._state["error"])
        return 2
    print(f"[模型] 就绪 {S._state['loadMs']/1000:.1f}s\n", flush=True)

    rows = []
    for i, (tag, truth, mono, sr, fn) in enumerate(items, 1):
        audio = audio_16k(mono, sr)
        rec = dict(item=tag, truth=truth, file=fn)

        t0 = time.time()
        p1, _ = run_p1(audio)
        rec["P1_p"] = round(p1, 4)
        rec["P1"] = "fraud" if p1 >= 0.5 else "normal"
        print(f"[{i}/{len(items)}] {tag} P1 单字    p={p1:.4f} "
              f"→ {rec['P1']:6s}  {time.time()-t0:.0f}s", flush=True)

        t0 = time.time()
        f2, txt2 = run_p2(audio)
        rec["P2"] = "?" if f2 is None else ("fraud" if f2 else "normal")
        rec["P2_raw"] = txt2[-260:]
        print(f"[{i}/{len(items)}] {tag} P2 JSON    → {rec['P2']:6s}  {time.time()-t0:.0f}s", flush=True)

        if a.p3:
            t0 = time.time()
            f3, scene, txt3 = run_p3(audio)
            rec["P3"] = "?" if f3 is None else ("fraud" if f3 else "normal")
            rec["P3_scene"] = scene[-160:]
            rec["P3_raw"] = txt3[-260:]
            print(f"[{i}/{len(items)}] {tag} P3 两轮    → {rec['P3']:6s}  {time.time()-t0:.0f}s",
                  flush=True)

        rows.append(rec)

    print("\n" + "=" * 78)
    hdr = f"{'素材':8s}{'真值':8s}{'P1单字':>10s}{'P2 JSON':>10s}"
    if a.p3:
        hdr += f"{'P3 两轮':>10s}"
    print(hdr)
    print("-" * 78)
    for r in rows:
        line = f"{r['item']:8s}{r['truth']:8s}{r['P1']:>10s}{r['P2']:>10s}"
        if a.p3:
            line += f"{r['P3']:>10s}"
        print(line)
    print("=" * 78)

    def acc(key):
        ok = sum(1 for r in rows if r.get(key) == r["truth"])
        return f"{ok}/{len(rows)}"

    print(f"\n正确率   P1={acc('P1')}   P2={acc('P2')}" + (f"   P3={acc('P3')}" if a.p3 else ""))
    print("\n【判读】")
    if acc("P2") != acc("P1"):
        print("  → 换回训练同构的 JSON 问法后判别力发生变化：恒答是是**问法**问题，可以改 prompt 修。")
    else:
        print("  → 两种问法正确率相同：问法不是主因，问题更可能在权重本身。")

    json.dump(rows, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\n明细 {a.out}")


if __name__ == "__main__":
    sys.exit(main() or 0)
