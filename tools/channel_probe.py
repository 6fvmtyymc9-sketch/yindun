#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
信道/格式敏感性探测 —— 判断 AntiFraud-SFT 在听「内容」还是在听「音频格式」
==========================================================================
起因：该模型对任意输入恒答「是」（静音 0.62、白噪声 0.53、正弦波 0.85、
14 段正常通话 14/14 全 fraud）。已知「训练集正样本占多数」这个解释已被推翻
（诈骗类仅 41.8%，见 docs/数据集核查_TeleAntiFraud_20261119.md 第十节）。

**本脚本只用公开数据，不含任何用户提供的录音。**

它把「格式」拆成两层，双向对照：

  外壳层 = 采样率标签、声道数、容器字节
  频谱层 = 4 kHz 以上到底有没有能量

模型的 _decode_audio 把一切统一成「单声道 16 kHz」，所以：

  ── 方向一：把「模型见过的 48k TTS」削窄到电话带宽 ──
     tts48   原始 48k 立体声 mp3（训练分布本人）
     tts8k   同一段降到 8k 再交回（内容一字不差，只是高频没了）
     → 若 P(是) 显著下跌：模型在吃「宽带 = 熟悉 = 诈骗」

  ── 方向二：把「真实 8k 电话」补上高频 ──
     orig8k  真实 8k 对话原样
     hfext   8k→16k，4–8k 用 0–4k 频谱镜像填上 -12 dB（不引入噪声）
     → 若 P(是) 显著上升：同向证据

  ── 方向三：只改外壳不改频谱 ──
     up48    真实 8k → 48k 立体声，内部再降回 16k，4–8k 仍为空
     → 若 orig8k ≈ up48：说明采样率/声道标签模型根本看不到

读法：
  orig8k ≈ up48        外壳无关，格式问题不在容器层
  tts8k ≪ tts48 且 hfext ≫ orig8k
                       模型在认「频带宽不宽」，即认「像不像训练集」
  全部四舍五入到同一值  与频带无关，问题在权重本身

用法：
  set PYTHONPATH=
  D:\\models\\.venv-sft\\Scripts\\python.exe tools\\channel_probe.py --sec 25
"""
import argparse
import base64
import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sft_server as S  # 复用模型加载与 _probe，避免两套前向逻辑

MD_MEET = r"D:/华为ict/外部数据集/magicdata/meeting/A CHINESE CONVERSATIONAL MEETING SPEECH CORPUS"
MD_ACCENT = r"D:/华为ict/外部数据集/magicdata/accent/Mandarin_Heavy_Accent_Conversational_Speech_Corpus"
AUDIO_ZIP = r"D:/下载/audio.zip"

# 真实 8k 电话对话（公开数据集，非用户录音）：(标签, 源路径, 起始秒)
MD8K = [
    ("md8k_A", rf"{MD_MEET}/20200327_2P_lenovo_iphonexr_66902.wav", 300),
    ("md8k_B", rf"{MD_MEET}/20200327_2P_lenovo_iphonexr_66906.wav", 600),
]
# 真实 16k 朗读（公开数据集）：用作「比 8k 宽、但还不是 48k」的中间档
MD16K = [
    ("md16k_A", rf"{MD_ACCENT}/G0001_S0001_0_SPK002.wav", 120),
]


def _resample(x, sr, target):
    """与 sft_server._resample 同一算法（线性插值），保证两边口径一致。"""
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


def _antialias(x, sr, target):
    """降采样前先低通。滑动平均近似，对语音够用（和 resample_wav.py 同思路）。"""
    if target >= sr:
        return x
    k = max(1, int(round(sr / target)))
    if k > 1:
        ker = np.ones(k, dtype="float32") / k
        x = np.convolve(x, ker, mode="same").astype("float32")
    return x


def hf_extend(x8):
    """8 kHz 语音 → 16 kHz，4–8 kHz 用 0–4 kHz 频谱镜像填上（-12 dB）。

    最简带宽扩展：不引入随机噪声、完全由原信号派生。内容和音色不变，
    唯一变化是「高频段到底有没有能量」。
    """
    x16 = _resample(x8, 8000, 16000)
    X = np.fft.rfft(x16)
    N = len(X)
    q = N // 2                     # 4 kHz 分界
    gain = 10 ** (-12 / 20.0)
    for j in range(1, q):
        src, dst = q - j, q + j
        if dst < N and src >= 0:
            X[dst] = np.conj(X[src]) * gain
    return np.fft.irfft(X, n=len(x16)).astype("float32")


def write_wav(path, x, sr, channels=1):
    import soundfile as sf
    if channels > 1:
        x = np.stack([x] * channels, axis=1)
    sf.write(path, np.clip(x, -1.0, 1.0), sr, subtype="PCM_16")


def load_slice(path, start, sec):
    import soundfile as sf
    info = sf.info(path)
    frames = int(sec * info.samplerate)
    d, sr = sf.read(path, start=int(start * info.samplerate), frames=frames,
                    dtype="float32", always_2d=True)
    mono = d.mean(axis=1) if d.shape[1] > 1 else d[:, 0]
    return mono, sr


def band_energy(path):
    """4 kHz 以上能量占比 —— 用来证明变体确实改到了频带，而不是嘴上说说。"""
    import soundfile as sf
    x, sr = sf.read(path, dtype="float32", always_2d=True)
    mono = x.mean(axis=1) if x.shape[1] > 1 else x[:, 0]
    mono = _resample(mono, sr, 16000)
    spec = np.abs(np.fft.rfft(mono)) ** 2
    tot = spec[1:].sum()
    return float(spec[len(spec) // 2:].sum() / tot) if tot > 0 else 0.0


def build_md(outdir, label, src, start, sec, src_rate):
    x, sr = load_slice(src, start, sec)
    tag = label
    out = []
    p = os.path.join(outdir, f"{tag}_orig.wav")
    write_wav(p, x, sr, 1)
    out.append((label, "orig", p))

    p = os.path.join(outdir, f"{tag}_up48.wav")
    write_wav(p, _resample(x, sr, 48000), 48000, 2)
    out.append((label, "up48", p))

    if src_rate == 8000:
        p = os.path.join(outdir, f"{tag}_hfext.wav")
        write_wav(p, hf_extend(x), 16000, 1)
        out.append((label, "hfext", p))
    return out


def build_tts(outdir, sec):
    """从 audio.zip 各抽 POS / NEG 一条，产出 tts48 与 tts8k 两版。"""
    import io
    import soundfile as sf
    import zipfile
    z = zipfile.ZipFile(AUDIO_ZIP)
    names = [i.filename for i in z.infolist() if i.filename.endswith(".mp3")]
    out = []
    for pre, tag in (("POS-", "tts-POS"), ("NEG-", "tts-NEG")):
        cand = sorted(n for n in names if f"/{pre}" in n)
        if not cand:
            print(f"[警告] audio.zip 里没有 {pre} 开头的条目", flush=True)
            continue
        name = cand[len(cand) // 2]
        d, sr = sf.read(io.BytesIO(z.read(name)), dtype="float32", always_2d=True)
        mono = d.mean(axis=1) if d.shape[1] > 1 else d[:, 0]
        mono = mono[:int(sec * sr)]

        p48 = os.path.join(outdir, f"{tag}_48k.wav")
        write_wav(p48, mono, 48000, 2)
        out.append((tag, "48k", p48))

        narrow = _antialias(mono, sr, 8000)
        p8 = os.path.join(outdir, f"{tag}_8k.wav")
        write_wav(p8, _resample(narrow, sr, 8000), 8000, 1)
        out.append((tag, "8k", p8))
        print(f"  [TTS] {tag} 源 {name.split('/')[-1]}", flush=True)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sec", type=float, default=25.0)
    ap.add_argument("--out", default=r"D:/华为ict/外部数据集/chan_probe")
    ap.add_argument("--skip-tts", action="store_true")
    ap.add_argument("--skip-md", action="store_true")
    ap.add_argument("--dry", action="store_true", help="只建变体不跑模型")
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    print(f"[准备] 输出 {a.out}，片段 {a.sec:.0f}s\n", flush=True)

    plan = []   # (素材, 变体, 路径, 真值)
    if not a.skip_md:
        for label, src, start in MD8K:
            if not os.path.exists(src):
                print(f"[跳过] 缺 {src}", flush=True)
                continue
            for lb, v, p in build_md(a.out, label, src, start, a.sec, 8000):
                plan.append((lb, v, p, "normal"))
        for label, src, start in MD16K:
            if not os.path.exists(src):
                print(f"[跳过] 缺 {src}", flush=True)
                continue
            for lb, v, p in build_md(a.out, label, src, start, a.sec, 16000):
                plan.append((lb, v, p, "normal"))

    if not a.skip_tts:
        for tag, v, p in build_tts(a.out, a.sec):
            plan.append((tag, v, p, "POS" if "POS" in tag else "NEG"))

    print(f"\n{'素材':14s}{'变体':7s}{'文件Hz':>8s}{'4k+能量':>9s}  文件")
    for lb, v, p, _ in plan:
        import soundfile as sf
        info = sf.info(p)
        print(f"{lb:14s}{v:7s}{info.samplerate:>8d}{band_energy(p)*100:>8.1f}%  "
              f"{os.path.basename(p)}", flush=True)

    print("\n[模型] 后台加载（CPU 约 2 分钟）…", flush=True)
    if a.dry:
        print("[dry] 只建变体，不加载模型。", flush=True)
        return 0
    S._load()
    if S._state["error"]:
        print("[致命] 加载失败：", S._state["error"], flush=True)
        return 2
    print(f"[模型] 就绪 {S._state['loadMs']/1000:.1f}s\n", flush=True)

    rows = []
    for i, (lb, v, p, truth) in enumerate(plan, 1):
        raw = open(p, "rb").read()
        t0 = time.time()
        try:
            meta = S._risk({"audio_b64": base64.b64encode(raw).decode(),
                            "name": os.path.basename(p)})
        except Exception as e:  # noqa: BLE001
            print(f"[{i:02d}/{len(plan)}] {lb:14s}{v:7s} 失败 {type(e).__name__}: {e}", flush=True)
            continue
        rows.append(dict(item=lb, variant=v, p=meta["p"], truth=truth,
                         srcRate=meta.get("srcRate"), dur=meta.get("durSec")))
        print(f"[{i:02d}/{len(plan)}] {lb:14s}{v:7s} P(是)={meta['p']:.4f}  "
              f"src={meta.get('srcRate')}Hz {meta.get('durSec')}s  {time.time()-t0:.0f}s",
              flush=True)

    # ---------- 汇总 ----------
    print("\n" + "=" * 66)
    print(f"{'素材':14s}{'变体':8s}{'P(是)':>9s}")
    print("-" * 66)
    for r in rows:
        print(f"{r['item']:14s}{r['variant']:8s}{r['p']:>9.4f}")
    print("=" * 66)

    def get(item, var):
        for r in rows:
            if r["item"] == item and r["variant"] == var:
                return r["p"]
        return None

    print("\n【结论】")
    for tag in ("tts-POS", "tts-NEG"):
        a48, a8 = get(tag, "48k"), get(tag, "8k")
        if a48 is not None and a8 is not None:
            print(f"  {tag}: 48k={a48:.4f} → 8k={a8:.4f}   Δ={a8-a48:+.4f}"
                  f"   {'↓ 削窄就掉分' if a8 < a48 - 0.05 else '→ 基本不变'}")
    for lb, _, _ in MD8K:
        o, u, h = get(lb, "orig"), get(lb, "up48"), get(lb, "hfext")
        if o is not None:
            print(f"  {lb}: orig8k={o:.4f}  up48={u if u is None else f'{u:.4f}'}  "
                  f"hfext={h if h is None else f'{h:.4f}'}"
                  f"   {'↑ 补高频就涨分' if (h is not None and h > o + 0.05) else '→ 基本不变'}")

    out = os.path.join(a.out, "result.json")
    json.dump(rows, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\n明细 {out}")


if __name__ == "__main__":
    sys.exit(main() or 0)
