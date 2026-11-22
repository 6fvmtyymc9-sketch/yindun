# AntiFraud-SFT 下一步计划（第二版）

> 2026-11-22 重写。第一版（11-18）见 git 历史。
> 改版原因：11-20 查明旧 `/risk` 接口的问法不对，模型本身没坏；训练改用文字数据，**不用再录训练录音**。
> 先读第一节「之前的问题」，改完再往下做。每一步都有「验收」，没达到就不进下一步。

---

## 零、现在在哪

**已经做好的（做得好，继续保持）：**

- 第 0 步融合规则：`mergeSft()` + 15 条单测
- 第 1 步评估集：30 通真人录音（正常 15、诈骗 15）已冻结，转写核对过，剔除了不合格样本，还记了 `labels.json`
- README 版本号、RDS 时长这些上次指出的问题已经改了

**现在的真实水平**（数字来自 `cloud/test/calls_eval/out-sft-*.jsonl`，以及老师 11-20 的实测）：

| 测试集 | 模式 | 模型自己的结论（is_fraud） | 按 p ≥ 0.85 |
|---|---|---|---|
| 30 通评估集 · 诈骗 15 | 音频 | 15 判对 | 15 判对 |
| 30 通评估集 · 正常 15 | 音频 | **10 通判成诈骗** | 0 通（正常最高 0.80，诈骗最低 0.85） |
| 30 通评估集 · 正常 15 | 文字 | **10 通判成诈骗** | 2 通 |
| holdout.json · 正常 15 | 文字 | 6 通判成诈骗 | — |
| CGoDial 政务热线 · 正常 30 | 文字 | **27 通判成诈骗** | — |

**结论：** 模型抓诈骗没问题，但一碰到「机构主动来电」（物业、快递、银行、网格员、医保、公积金）就倾向于判诈骗。
现在能把误报压下去，靠的是 p ≥ 0.85 这条线，而正常和诈骗之间只差 0.05 一档，非常脆。

**本轮目标：** 用 LoRA 微调，让模型**自己的结论**在正常机构来电上说「不是诈骗」，同时诈骗不漏。

---

## 一、之前的问题（先改这些）

### 1. 「SFT 恒答是」的原因写错了

`docs/项目进度总览_20261122.md` 第四节、第七节第 3 条，以及 `docs/SFT恒答是根因排查_20261120.md`，
还写着「是数据构造 bug、模型没学过不构成诈骗」。**这个结论不成立：**

- 真正的原因是旧 `/risk` 接口要求模型「只答一个字」，再比较第一个字是「是」还是「否」的概率。
  模型从没被这样训练过，这个数和输入基本无关，所以连静音都能得到 0.62
- 证据就在你们自己的结果里：用 `/judge` 跑 30 通，**有 5 通正常通话模型明确输出了 `is_fraud: false`**。
  如果真没学过「否」，一次都不会出现
- `sft.zip` 里没有 false 这件事可以保留，但只能写成「公开发布的数据打包有问题」，不能当作「权重坏了」的证据

**要改：** 上面三处改成「旧接口问法错误导致，改用 `/judge` 后模型能判否」；第七节第 3 条「线上不接 SFT」的原因
改成「机构来电误报还高，等微调验证」。

### 2. 「文本模式没有判别力」和你们自己的数据矛盾

进度总览第七节第 4 条写「SFT 判文字……正常短信误报 93.3%，文本模式对 SFT 没有判别力」。
93.3% 是旧 `/risk` 接口的数字。你们自己用 `/judge` 跑文字是诈骗 15/15、正常误报 2/15（p ≥ 0.85）。
**删掉这条，或者写明是旧接口的数字。**

### 3. 基线只报了好看的那个口径

「SFT 音频 0/15 误报」只在 p ≥ 0.85 时成立，模型自己的结论是 **15 通正常里 10 通判成诈骗**。
p 是模型自己随口给的 confidence 换算的，只有 0.7、0.75、0.8、0.85、0.9 这几档，正常最高 0.80、诈骗最低 0.85，
差一档就会翻。**`docs/评估基线_30通_20261121.md` 和进度总览里，两个口径都要写：**

> SFT 音频：模型结论误报 10/15；按 p ≥ 0.85 误报 0/15（正常最高 0.80，与诈骗最低 0.85 相差一档）

### 4. 「学校 GPU 容器连不上」不是阻塞

容器一直在线（11-22 老师还连上跑过测试），端口也没变。连不上是因为你不在校园网，
**连学校 VPN 或到学校再连就行**。进度总览第六节这条删掉。

### 5. 「先用 sft_fixed 跑 200 条小重训，验证能不能学会说否」取消

模型已经会说「否」了（见第 1 条）。`sft_fixed` 是 TeleAntiFraud 的 TTS 数据，SFT 本来就是用它训练的，
再训一遍等于重复。这个实验不做。

### 6. 第 3 步「还要录 60~100 通真人训练录音」取消

线上是先转成文字再判断，训练用**真实通话转写出来的文字**就够了（见第三步），不用再录训练录音。
评估集已经录好，那 30 通**永远不能拿来训练**。

### 7. 以前遗留、还没改完的

- 冒烟测试（16 条、全是「否」、2 步）不能写成「训练跑通了」
- 对账引用的提交号 `6cae3b7` 在仓库里不存在
- Telecom_Fraud_Texts_8 的分析有两处要改：TeleAntiFraud 的 `audio.zip` 实际有 12.7 GB（拿到 0 字节是因为没登录 Hugging Face、没同意协议）；
  XXX 占位符在正常短信里更多（46.5%，诈骗大多数类别只有 5%～8%），风险是漏报，不是误报。
  98.3% 要写成「在 11,253 条合成诈骗话术上规则命中 98.3%」，不能写成召回率

---

## 二、第一步：准备环境（半小时）

1. 连上学校 VPN，登录容器：`ssh -p 43998 zkpk@10.8.110.32`
2. 拉取最新代码：`cd ~/yindun && git pull`
3. 建自己的目录：`mkdir -p ~/work/<名字>/{data,eval,out}`
4. 确认服务是新版本：`curl -s 127.0.0.1:8000/health` 返回里有 `"lora": null`

**验收：** `~/yindun/tools/` 下能看到 `build_judge_data.py` 和 `eval_judge.py`。

---

## 三、第二步：准备诈骗文字（半天）

正常样本脚本会自动从 CGoDial 里取，你只需要准备**诈骗文字**，存成 `~/work/<名字>/data/fraud_texts.jsonl`，每行一条：

```json
{"text": "喂，您好，我是市公安局的……", "label": "fraud", "type": "冒充公检法"}
```

**来源（按优先级）：**

1. **TeleAntiFraud 里的通话文字**：你手上有 `sft.zip`，先看里面有没有对话文字（类似 `talk_list` 的字段）。
   有的话优先用，诈骗、正常都取：
   - 诈骗的写 `"label": "fraud"`，原数据里的判定理由放进 `"reason"`
   - **正常的也取一些**，写 `"label": "normal"`，脚本会让它最多占正常样本的一半。
     这样正常样本不全是 CGoDial 一种说话风格，模型就不会学成「政务热线风格 = 正常」
2. **Telecom_Fraud_Texts_8**：只取和老人相关的 5 类（1 公检法、2 贷款征信、3 冒充客服、4 冒充领导熟人、5 刷单返利），
   **去掉带 XXX 占位符的**，`type` 写类别名。label0 那些营销短信不要用

**不能用的：** `holdout.json`、`calls_eval/` 里的录音和转写。这两份是评估集。

**验收：** 诈骗文字至少 1,000 条；随手翻 20 条，确认是诈骗内容、没有乱码。

---

## 四、第三步：生成训练数据（10 分钟）

```bash
cd ~/yindun
python3 tools/build_judge_data.py \
  --cgodial ~/data/cgodial/cgodial_flow_dialogs.jsonl \
  --extra ~/work/<名字>/data/fraud_texts.jsonl \
  --out ~/work/<名字>/data --per-class 1000
```

脚本做了这几件事（不用你手动做，但要知道为什么）：

| 处理 | 为什么 |
|---|---|
| 去掉「客服：」「来电人：」这类角色标签 | 线上 SIS 转写本来就没有说话人；只有 CGoDial 带标签，不去掉的话模型会学成「有标签 = 正常」 |
| 长度统一截到 60～800 字 | 防止模型学成「长 = 正常、短 = 诈骗」（实测两类长度中位数都在 320 字左右） |
| 第一轮场景一律写「咨询客服」 | 场景答案不泄露标签 |
| 留出 300 通 CGoDial（按场景分层）到 `heldout_cgodial.jsonl` | 这 300 通**永远不训练**，第五步、第七步专门用来算政务热线误报率 |
| 格式和 `/judge` 完全一样 | 提示词直接从 `sft_server.py` 导入，训练和推理一字不差 |

**验收：**

- `summary.json` 里 fraud 和 normal 数量相等，`heldout_cgodial` 是 300
- 打开 `train.jsonl` 看 3 条：每条 4 段对话（user → assistant → user → assistant），最后一段是带 `is_fraud` 的 JSON

---

## 五、第四步：微调前先测一遍（约 1 小时，后台跑）

微调前后要在**同一批数据**上比，所以先用原始模型测：

```bash
cd ~/yindun
nohup python3 tools/eval_judge.py --url http://127.0.0.1:8000 \
  --input ~/work/<名字>/data/heldout_cgodial.jsonl --out ~/work/<名字>/eval/base_cgodial.jsonl \
  > ~/work/<名字>/eval/base_cgodial.log 2>&1 &
# 等上面跑完再跑这个（服务一次只处理一条，同时跑只会排队）
nohup python3 tools/eval_judge.py --url http://127.0.0.1:8000 \
  --holdout cloud/test/holdout.json --out ~/work/<名字>/eval/base_holdout.jsonl \
  > ~/work/<名字>/eval/base_holdout.log 2>&1 &
```

日志最后两行就是汇总，两个口径都有（模型结论、p ≥ 0.85）。30 通评估集已经有结果（`out-sft-judge.jsonl`、`out-sft-text.jsonl`），不用重跑。

**验收：** 拿到三份微调前的数字：CGoDial 300 通误报、holdout 35 条、30 通评估集。

---

## 六、第五步：LoRA 微调（2～4 小时）

1. **在群里说一声**，然后停掉推理服务（训练和推理加起来会爆显存）：
   ```bash
   for pid in $(pgrep -f "^python3 tools/sft_server.py"); do kill $pid; done
   nvidia-smi        # 确认显存基本空了，没有别人在训练
   ```
2. 开始训练（用你之前跑通的 `~/venv-swift`）：
   ```bash
   cd ~/work/<名字>
   nohup ~/venv-swift/bin/swift sft \
     --model ~/models/JimmyMa99/AntiFraud-SFT \
     --train_type lora --lora_rank 16 --lora_alpha 32 --target_modules all-linear \
     --freeze_vit true --freeze_aligner true \
     --dataset data/train.jsonl --val_dataset data/val.jsonl \
     --torch_dtype bfloat16 --max_length 2048 \
     --learning_rate 1e-4 --num_train_epochs 2 \
     --per_device_train_batch_size 1 --gradient_accumulation_steps 8 \
     --loss_scale last_round \
     --eval_steps 50 --save_steps 50 --save_total_limit 3 \
     --output_dir out > train.log 2>&1 &
   tail -f train.log
   ```
   - `--loss_scale last_round`：只在最后一轮回答（带 is_fraud 的那段）上算损失，第一轮场景答案是固定模板，不用学。
     如果报参数不认识，先跑 `~/venv-swift/bin/swift sft --help | grep -A3 loss_scale` 看你的版本支持哪些值
   - 2,000 条 × 2 轮，L40 上预计 2～4 小时
3. **训练完马上做两件事：**
   ```bash
   # 1) 恢复推理服务（原始权重），别让演示环境一直停着
   ~/start_sft.sh
   # 2) 把 LoRA 拷回自己电脑（在你电脑上执行）。平台一删容器，磁盘就清空了
   scp -P 43998 -r zkpk@10.8.110.32:~/work/<名字>/out ./lora_backup/
   ```

**验收：** `train.log` 里验证集 loss 在下降；`out/` 下有 `checkpoint-xxx/adapter_model.safetensors`。
**注意：loss 下降不代表变好，只有第六步的结果算数。**

---

## 七、第六步：加载 LoRA，在同一批数据上重测（约 1 小时）

另起一个带 LoRA 的服务，放在 8001 端口，8000 继续跑原始模型，方便对比：

```bash
CK=$(ls -d ~/work/<名字>/out/v*/checkpoint-* | tail -1)     # 最后一个检查点
cd ~/yindun && SFT_DEVICE=cuda SFT_LORA=$CK ANTIFRAUD_DIR=$HOME/models/JimmyMa99/AntiFraud-SFT \
  setsid nohup ~/venv-swift/bin/python3 tools/sft_server.py --host 127.0.0.1 --port 8001 \
  > ~/work/<名字>/sft_lora.log 2>&1 < /dev/null &
curl -s 127.0.0.1:8001/health      # "lora" 字段应该是你的检查点路径
```

（必须用 `~/venv-swift/bin/python3` 启动，因为里面装了 peft。这条路径老师已经用你之前的冒烟检查点试过，能正常加载。）

然后把第四步的两条命令再跑一遍，`--url` 改成 `http://127.0.0.1:8001`，输出文件名把 `base_` 改成 `lora_`。
30 通评估集也用 8001 重跑音频和文字两种模式（用你们之前跑 `out-sft-judge.jsonl` 的那个脚本，只改地址），
结果存成 `out-sft-lora-judge.jsonl` 和 `out-sft-lora-text.jsonl`。

**填这张表：**

| 测试集 | 口径 | 微调前 | 微调后 |
|---|---|---|---|
| CGoDial 留出 300（正常） | 模型结论误报 | | |
| holdout 诈骗 20 | 模型结论判对 | | |
| holdout 正常 15 | 模型结论误报 | | |
| 30 通 · 诈骗 15（音频） | 模型结论判对 | 15 | |
| 30 通 · 正常 15（音频） | 模型结论误报 | 10 | |
| 30 通 · 正常 15（文字） | 模型结论误报 | 10 | |
| 30 通（音频） | p ≥ 0.85 误报 / 漏报 | 0 / 0 | |

**验收（三条都满足才算成功）：**

1. CGoDial 留出集误报明显下降（比如从八九成降到两成以下）
2. 诈骗不漏：holdout 诈骗至少 19/20，30 通诈骗 15/15
3. 30 通正常的「模型结论误报」下降

**没达到怎么办：**

| 现象 | 调整 |
|---|---|
| 误报没降 | `--per-class` 加到 2000，或 `--num_train_epochs 3` |
| 误报降了，但诈骗开始漏 | 学习率降到 5e-5；诈骗文字多加一些来源 |
| CGoDial 降了，30 通正常没降 | 说明学到的是「政务热线风格」，要多加 TeleAntiFraud 的正常对话（第二步第 1 条） |

每次调整只改一个参数，每一轮都记下参数和结果。

---

## 八、第七步：收尾（半天）

1. 新建 `docs/LoRA微调实验_<日期>.md`，写清楚：
   - 训练数据：来源、条数、正负比例，`summary.json` 原样贴上
   - 参数：直接贴 `out/.../args.json` 里的关键项
   - 第六步那张表，**写原始计数**，并写明「30 通是小样本，结论是有改善迹象」
   - 三个具体例子：一通原来误报、现在判对的正常通话，附微调前后的 reason 对比
2. 结果 jsonl（不含音频）提交进仓库，**LoRA 权重不进仓库**（太大），放自己电脑和网盘
3. 要不要接进线上服务、接了以后阈值怎么定，等表格出来**和老师一起定**，不要自己改线上配置
4. 用完 8001 的服务记得关掉：`for p in $(pgrep -f "^/home/zkpk/venv-swift/bin/python3 tools/sft_server.py"); do kill $p; done`

---

## 九、时间

| 步骤 | 预计 |
|---|---|
| 一 改文档里的错误结论 | 半天 |
| 二～四 环境、诈骗文字、生成数据 | 1 天 |
| 五 微调前测试 | 1 小时（后台） |
| 六 训练 | 半天 |
| 七 重测 + 调参 | 1～2 天 |
| 八 收尾 | 半天 |

## 十、不要做的事

- 不要拿 30 通评估集、`holdout.json`、`heldout_cgodial.jsonl` 去训练，也不要照着它们的错例补数据
- 不要再用「只答一个字：是或否」的格式训练，也不要再用 `/risk` 的 p 下结论
- 不要只报好看的口径：模型结论和 p ≥ 0.85 两个都写
- 不要在别人训练时启动训练；不要通过 SSH 远程执行 `pkill -f sft_server.py`（会把自己的连接也杀掉），用上面写的 `pgrep -f "^python3 ..."` 写法
- 不要覆盖 `~/models` 里的原始权重；不要改线上 `SFT_URL`
- 对账里每个 ✅ 都要附上提交号或文件路径；没提交进仓库的不算完成
