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

---

## 八、训练参数核查：val 指向 test split，但没参与模型选择（2026-11-20 补）

> 起因：要判断「拿 TeleAntiFraud 的 test split 当评估集，算不算自己考自己」。
> 依据：模型仓库自带的 `args.json`（ms-swift 训练参数，301 个键），不是推测。

### 实际使用的数据文件

```
dataset              = ['/root/code/Fraud_SFT/call_r1/swift_sft_data_total/total_train_wpd.jsonl']
val_dataset          = ['/root/code/Fraud_SFT/call_r1/swift_sft_data_total/total_test_swift3_demo.jsonl']
split_dataset_ratio  = 0.0
load_best_model_at_end = False
```

三个事实：

1. **train 和 val 是两个不同的文件**（`total_train_*` / `total_test_*`），不是同一份切出来的
   —— `split_dataset_ratio=0.0` 印证：没有二次切分。
2. **val 直接指向官方 test split**（对应上文 6,807 条那份里的一个 demo 子集）。
   即：作者训练时读了 test 文件、算了 loss。
3. **但 `load_best_model_at_end = False`** —— 交付的是**最后一步的权重**，
   没有按 val loss 挑 checkpoint（`eval_steps=None`、`save_steps=2000`）。

### 结论（按证据强度分级，别说过头）

| 判断 | 强度 |
|---|---|
| test split 的 loss 被记录过 | **确定**（写在 args.json 里） |
| test split 参与梯度更新 | **否**（只在 val_dataset，不在 dataset） |
| test split 影响最终权重选择 | **否**（`load_best_model_at_end=False`） |
| 所以 test split「被污染」 | **不成立**，最多算「轻度窥探」 |

**这里要更正一个容易说过头的说法**：不能讲「作者的 test 被当验证集用，所以污染了」。
准确说法是「val 指向了 test 文件，但因为没按 val 选模型，test 仍算干净」。

### 但这不改变评估策略，理由换成另外三条

「不污染」只是必要条件，不是充分条件：

1. **同一份音频测不出东西** —— 公开版 34,000 条音频 100% 是 `tts_*`、48 kHz 立体声。
   拿它当评估集，考的是「像不像合成音」，不是「骗不骗」。这正是恒答「是」的来源（见第三节）。
2. **外部那四份数据一条诈骗都没有** —— MobileCS / CGoDial / HKUST / MagicData-RAMC
   全是正常对话。拿它们算召回率，分母为零，那一栏只能写「—」。**只能测误报，测不了漏报。**
3. **唯一 8k 电话信道的 HKUST 要 LDC 会员**（149 小时，最贴），28 号前要不到。

### 对 30 通方案的修正（待确认后改 README）

既然正常样本有公开语料可补，自录的价值集中在**诈骗正样本**：

| 类别 | 原方案 | 可调整 |
|---|---|---|
| 诈骗 15 通 | 必须自录 | **仍然必须自录**，外部拿不到 |
| 正常 15 通 | 必须自录 | 可减到 6~8 通，其余用本机已有的 MagicData 两个子集补（见下） |

### 本机已有音频语料实况（2026-11-20 逐文件实测，更正此前两处记错）

> 此前一直记作「AISHELL-2 十段 + MagicData-RAMC 四段」，**两处都不对**。
> 实查 `D:\华为ict\外部数据集\magicdata\`，14 段全部来自 MagicData 的**另外两个子集**，
> 本机没有 AISHELL-2，也没有 RAMC。参数由 `wave` 读 WAV 头得到，不是估计。

| 子集 | 段数 | 采样率 / 声道 | 单段时长 | 内容 | 转写 |
|---|---|---|---|---|---|
| `Mandarin_Heavy_Accent_Conversational_Speech_Corpus` | 10 | **16 kHz** / 单声道 | 约 20.6 分钟 | 双人自由对话，带重口音 | ✅ 逐句带 `[起,止]` 时间戳 |
| `A CHINESE CONVERSATIONAL MEETING SPEECH CORPUS` | 4 | **8 kHz** / 单声道 | 约 32 分钟 | 双人（2P）**远程通话** | ✅ 逐句带 `[起,止]` + 说话人编号 |

**关键点：8 kHz 那四段才是电话带宽。**

`meeting` 子集名字叫会议，实际是双人远程通话——转写开头就是：

```
[4.655,6.051] 1 男,普通话 哎，小陈听得到吧？
[6.633,7.399] 2 男,普通话 可以听到
```

「听得到吧 / 可以听到」是通话开场，且 8 kHz 单声道正是窄带电话的标准参数。
**这是本机唯一与线上场景信道对齐的音频**，共约 2 小时，可直接切段做正常样本。
16 kHz 那份（重口音对话）只能当文本侧难负样本，音频喂进 SFT 会被当成另一种分布。

两条子集都带逐句时间戳转写，切段时不必再跑 ASR，直接按时间戳裁。

注意：8k 那四段是**会议议题**（讨论员工住宿方案），不是客服/办事类，语料偏「同事之间」；
样本量 4 段，够跑通、不够统计学结论，报告里要如实写「4 段 / 约 2 小时」。

### 外部四份数据的可得性（28 号前）

| 数据集 | 语言 / 形式 | 现在能不能拿到 | 用途 |
|---|---|---|---|
| CGoDial | 中文，客服对话**文本** + ASR 噪声 | 开源，今天可拉 | 文本模式难负样本 |
| MobileCS | 中文，10 万通客服**转写** | SereTOD 需申请，周期未知 | 文本模式难负样本（来不及就算） |
| HKUST | 粤语/普通话，149h **8k 电话音频** | LDC 付费会员 | 最贴，但要不到 |
| MagicData-RAMC | 中文，180h 手机**近场**录音 | OpenSLR SLR123 免费，已有 4 段 | 补正常样本 |

### 一句话答「是不是自己考自己」

**不是**。但这个模型的 test split 也用不了，因为它的音频是合成的。
「没用那两份外部数据」不等于「随便哪份外部数据都能当考官」——
考官必须和考生面对的**真实场景同分布**，而不是只要「没被判过卷」。

---

## 九、TeleAntiFraud 数据本体：**能下，是我之前判断错了**（2026-11-20 19:50 更正）

老师在 `docs/SFT下一步计划.md` 第 3 步明确写「诈骗通话 60~100 条，
**一半另外录，一半从 TeleAntiFraud 里抽**」。所以这份数据不是可选项，是计划的一部分。

> ### ⚠️ 重要更正
> 19:25 我写的是「两个入口都堵着，只能找老师要」。**这个结论是错的。**
> 错在把「命令行匿名请求 403」当成了「整份数据拿不到」。
> 实际证据：**11-19 14:21~14:27 已经用浏览器成功下到 4 个文件**：
>
> ```
> /d/下载/binary_classification.zip   60,284 B   11-19 14:21
> /d/下载/sft.zip                  4,390,686 B   11-19 14:27
> /d/下载/fraud_example_1.mp3      1,905,645 B   11-19 14:27
> /d/下载/fraud_example_2.mp3        361,581 B   11-19 14:27
> ```
>
> **结论：网页登录状态下能下，命令行匿名下不了。** `audio.zip`（12.7 GB）同理，
> 只是体积大。不用再找老师要。

### 命令行为什么 403（19:50 复测）

| 请求 | 结果 |
|---|---|
| `GET /api/v1/datasets/JimmyMa99/TeleAntiFraud` | **200**（详情可读，`Visibility:1` 公开） |
| `GET .../repo?FilePath=dataset_manifest.json` | **403**（无 cookie） |
| `GET .../repo/tree?Revision=master` | **403** `无权访问该数据集` |
| 带上 `~/.modelscope/credentials/session`（32 字节 UUID） | 仍 **403** —— 那不是有效 token |

`~/.modelscope/credentials/session` 里只有 32 字节的 `2da31da8...`，是本地旧 session id，
**不是可用的访问凭证**。所以命令行这条路走不通，必须借浏览器的登录态。

### 还有一处也要更正：11-18 存下来的"文件"是假文件

`D:/华为ict/datasets/TeleAntiFraud/` 下那 7 个文件（`probe.mp3`、`t.bin`、
`dataset_manifest.json` 等）**全部只有 160~176 字节**，是当时下载失败存下来的 403 错误响应，
不是真数据。别拿它们当"已下载"。

### 现在该怎么下

**路径：浏览器登录 ModelScope → 数据集文件页 → 点 `audio.zip`**

```
https://modelscope.cn/datasets/JimmyMa99/TeleAntiFraud/files
```

- 12.7 GB，D 盘有 612 G，空间没问题
- 命令行想接手的话，把浏览器这套 Cookie 给出来，可以用 `curl -C -` 断点续传，比浏览器稳
- 下下来先做一件事：**验证是不是真电话信道**（8k 单声道 vs 48k 立体声）。
  按第二、三节的核查，公开版音频是 ChatTTS 合成的 48k 立体声，
  如果 `audio.zip` 里也是这个参数，那它**只能进训练集**（老师第 3 步允许），
  **不能进第 1 步的冻结评估集**

### 这份数据到底是什么（不变）

| 文件 | 大小 | 内容 |
|---|---|---|
| `dataset_manifest.json` | 347 B | 发布清单 |
| `binary_classification.zip` | 60.28 KB | 二分类 4,000 + 400 条 |
| `sft.zip` | 4.39 MB（解压 139 MB） | 多轮 SFT 27,146 + 6,807 条 |
| `audio.zip` | **12.74 GB** | 音频本体 |
| `preview/*.mp3` | 3 个 | 抽样，48 kHz 立体声 TTS |

### 要跟老师说的话（改过了）

1. ~~要 `audio.zip`~~ → **不用了，我们能下，之前是我判断失误**
2. 它的音频是 ChatTTS 合成的（摘要原文 `renders validated dialogues as role-matched speech`），
   按您第 1 步「不要用 TTS」的要求，**只进训练集，不进评估集**，这条我们记着
3. 顺带：第 1 步写的「m4a 服务端解不开」不准确 —— `cloud/src/Asr.js` 第 30 行
   `m4a: 'audio/mp4'`，m4a 单条 60 秒内可直接转写，只有超长且切不动才失败。
   给乙的录音要求里我们已经改成「m4a 也行，单条别超 60 秒」
