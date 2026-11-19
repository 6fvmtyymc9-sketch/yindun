# TeleAntiFraud 公开版核查：音频 100% 为 TTS 合成

> 2026-11-19 核查。样本来自 ModelScope `JimmyMa99/TeleAntiFraud`（需登录授权，已获批）。
> 结论：**这份公开数据不能当「真人录音」使用，也不能当冻结评估集。**
> 本文所有数字均可用文末命令复现，不做估计。

---

## 一、核查了什么

| 文件 | 大小 | 用途 |
|---|---|---|
| `dataset_manifest.json` | 347 B | 发布清单 |
| `binary_classification.zip` | 60.28 KB | 二分类 4,000 + 400 条 |
| `sft.zip` | 4.39 MB（解压 139 MB） | 多轮 SFT 27,146 + 6,807 条 |
| `preview/*.mp3` | 3 个 | 音频本体抽样 |

`audio.zip`（12.74 GB）**没有下载**——见第四节，没有必要。

---

## 二、三重证据

### 证据 1：音频路径 100% 落在 `tts_*` 目录

对 `sft.zip` 的两个 jsonl 逐行解析（34,000 条记录，34,000 条音频引用）：

```
sft/train.jsonl   27,146 条 | 音频引用 27,146 条 | 含 "tts" 27,146 = 100.0%
sft/test.jsonl     6,807 条 | 音频引用  6,807 条 | 含 "tts"  6,807 = 100.0%
```

`binary_classification` 的 4,400 条同样 100%：

```
sft/train.jsonl 一级目录分布
  NEG-multi-agent-3    4,617        全部形如
  NEG-multi-agent-2    3,945        audio/POS-imitate-4/tts_test1139/tts_test1139.mp3
  NEG-imitate-10       3,756        audio/NEG-multi-agent-1/tts_fraud_00711/tts_fraud_00711.mp3
  POS-imitate-6        2,232
  POS-imitate-5        2,214
  POS-imitate-4        2,208
  POS-imitate-7        2,184
  NEG-imitate-11       2,004
  NEG-imitate-12       1,983
  POS-multi-agent-9    1,136
  NEG-multi-agent-1      867
```

目录名 `tts_test` / `tts_fraud`，一个例外都没有。

### 证据 2：作者自己在仓库里放了 ChatTTS 的调用脚本

GitHub `JimmyMa99/TeleAntiFraud` 的 `ChatTTS/` 目录有 107 个文件，README 第 133 行起：

```
## 🎙️ Voice Synthesis with ChatTTS
   fastapi dev ChatTTS/examples/api/main_new_new.py --host 0.0.0.0 --port 8006
   bash ChatTTS/examples/api/run*.sh
```

即：作者提供了一整套「用 ChatTTS 生成通话音频」的流水线。这与 `tts_*` 路径互相印证。

### 证据 3：音频参数是「电脑合成 + 转码」，不是电话信道

三个 preview 样本的 MP3 帧头解析：

| 文件 | 大小 | 时长 | 采样率 | 声道 | 比特率 | 编码器 |
|---|---|---|---|---|---|---|
| `normal_example.mp3` | 417,645 B | ≈52 秒 | **48,000 Hz** | **立体声** | 64 kbps CBR | Lavf / LAME |
| `fraud_example_1.mp3` | 1,905,645 B | ≈238 秒 | **48,000 Hz** | **立体声** | 64 kbps CBR | Lavf / LAME |
| `fraud_example_2.mp3` | 361,581 B | ≈45 秒 | **48,000 Hz** | **立体声** | 64 kbps CBR | Lavf / LAME |

**这一条是决定性的。** 真实电话通话不存在 48 kHz 立体声：

- 传统电话（PSTN / GSM）：**8 kHz 单声道**（窄带，3.4 kHz 以上频段被砍掉）
- VoLTE / 微信语音：**16 kHz 单声道**（宽带，上限 7 kHz）
- 48 kHz 立体声 是录音棚、音乐、TTS 合成的标准格式

再叠加 `Lavf`（ffmpeg 的封装标识）与统一 64 kbps CBR，可以确定：**音频是电脑合成后经 ffmpeg 批量转码的产物，不是任何一通真实电话的录音。**

### 对照实验：同一台机器上的两组音频

用同一个解析器（`tools/probe_mp3.py`）跑 TeleAntiFraud 的样本和我们自己的测试录音，差异是一目了然的：

| | TeleAntiFraud 公开版 | 我们自己的测试录音 |
|---|---|---|
| 采样率 | **48,000 Hz** | **16,000 Hz** |
| 声道 | **立体声** | **单声道** |
| 比特率 | 64 kbps CBR | 24 kbps |
| MPEG 版本 | MPEG1 Layer3 | MPEG2.5 Layer3 |
| 单个文件大小 | 417 KB / 52 秒 | 87 KB / 29 秒 |
| 同长度体积比 | **约 2.7 倍** | 1 倍 |

两组都带 `Lavf` + `LAME` 标记（都是 ffmpeg 转的），但**只有 TeleAntiFraud 是 48k 立体声**。我们那三条是模拟器通话音（16k 单声道），体积只有同长度合成音的 1/2.7。

这条对照消除了「可能只是编码参数不同」的辩解：**采样率和声道数是音频的物理属性，不是编码选项。**

---

## 三、这解释了「AntiFraud-SFT 恒答是」

11-19 的可用性复核记录过一个反常现象：

```
静音        0.6225 → fraud
白噪声      0.5312 → fraud
440Hz 正弦  0.8520 → fraud    ← 完全不含语音，也给 0.85
14 段正常通话 14/14 全 fraud
AUC 仅 0.763，最优阈值仍 93% 误报
```

现在有了一个能自洽的解释：**模型的判别依据可能根本不是「内容骗不骗」，而是「这段音频像不像 TTS 合成的」。**

它训练集里的正负样本全部来自 ChatTTS + 同一套转码流水线，音色、频谱包络、编码痕迹高度一致。模型只要抓住「ChatTTS 音色 + 48k 立体声」这一族特征，在训练集上就能拿到极高准确率——而真实的 8k 单声道电话录音在这个特征空间里完全落在分布外，于是被无差别地判成 fraud。

**这是推测，不是结论**，但它同时解释了「为什么 AUC 只有 0.763」「为什么静音也给 0.62」两个现象，且可证伪：把一段真实 8k 通话升采样到 48k 立体声再送进去，如果分数显著变化，就说明模型在吃格式特征。

---

## 四、所以 `audio.zip` 那 12.74 GB 不用下了

下下来得到的也是同一批 TTS 音频。它对我们有确切价值的场景只剩一个：**当预训练语料，让模型先学会「通话里的人在说什么」**，而不是当「真人诈骗电话」。

但这个用途有代价：在 48k 立体声 TTS 上预热过的模型，会有明确的格式偏置，后面必须用真人 8k 数据做对齐微调，否则线上依然崩。

---

## 五、这份数据还剩什么可用

`sft/train.jsonl` 的结构是多轮 SFT，每条形如：

```
audios: ["audio/NEG-imitate-11/tts_test1914/tts_test1914.mp3"]
messages: system → user(场景分类任务) → assistant(JSON 答案) → user(诈骗判断任务) → ...
answers: "fraud"
prompts / answers 各一份，可直接用于 ms-swift
```

**有价值的部分是文本侧**：

- 一轮完整的「场景分类 → 诈骗判断」推理链，中文，带 reason 和 confidence
- 27,146 条训练 + 6,807 条测试，标签严格配平
- 八类诈骗类型（投资/钓鱼/身份盗窃/彩票/银行/绑架/客服/邮件）

我们的规则引擎和 DeepSeek 都缺「说理」能力——现在 `Advice.js` 是模板拼接。这份数据的答案文本可以作为**说理风格**的监督信号。

**没价值的部分是音频**：不能当真人，不能当独立评估集。

---

## 六、对微调计划的三个影响

1. **冻结评估集仍然必须自己录 30 通。** 现在理由更硬：公开可得的同类数据全是 TTS，这一项外部替代不了。乙那份《录音执行单》优先级不能降。

2. **训练时要做采样率对齐。** 如果先用这份 48k 立体声数据预热，正式训练必须混入真实 8k/16k 样本，并在推理前统一重采样。否则模型学到的是「格式分类器」。

3. **答辩时说「307 小时真人录音」会被问穿。** README 写的 307 小时是真的，但那 307 小时里公开出来的这部分是 TTS。**只能说「我们核过公开版，音频是合成的，所以评估集自己录」**——这句话反而能体现做过核查。

---

## 七、复现命令

```bash
# 路径统计（1 分钟）
python - <<'PY'
import zipfile, json, io, re, collections
z = zipfile.ZipFile("sft.zip")
for n in ["sft/train.jsonl","sft/test.jsonl"]:
    refs = tts = 0
    for line in io.TextIOWrapper(z.open(n), encoding="utf-8"):
        x = json.loads(line)
        for u in x.get("audios", []):
            refs += 1
            if "tts" in u.lower(): tts += 1
    print(n, refs, tts, f"{tts/refs*100:.1f}%")
PY

# 音频参数（需 mp3 帧头解析，见 tools/probe_mp3.py）
# 关键看 sample rate 与 channel：48000 Hz + 立体声即非电话录音
```
