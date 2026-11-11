# AntiFraud-SFT 在华为云 GPU 上跑起来

> 对应《模型选型》4.2：「约 17GB，只能放云端，需要 GPU。手机端不可能部署」，
> 「建议定位：作为对比实验放进报告」。
> 本文只解决一件事：**怎么在一台有 GPU 的机器上把它跑出数字**。

## 一、为什么必须换机器

本机实测（2026-11-10）：

| 项 | 数值 |
|---|---|
| 模型权重 | 16 GB（Qwen2-Audio-7B，bf16） |
| 本机总内存 | 15.8 GB |
| 无独显 | Intel Iris Xe，共享 1 GB |
| 结果 | 加载后进程占 9.8 GB、可用内存剩 0.7 GB，42 秒录音跑 5 分钟未出结果，连接断开 |

**权重比整台机器的内存还大**，加载就开始用交换分区，之后每一步前向都在磁盘上颠簸。
这不是代码问题——`tools/sft_server.py` 已经是单次前向、只读「是」「否」两个 token 的概率，
没有用 `generate`。

线上 ECS（`123.249.25.139`）是 **2 核 / 2 GB / 无 GPU**，同样装不下，所以
`/api/health` 一直如实报 `sft.enabled: false`。

## 二、要一台什么样的机器

| 项 | 最低 | 建议 |
|---|---|---|
| 显存 | 16 GB（bf16 刚好装下，无余量） | 24 GB（A10 / L4 / 3090 级） |
| 内存 | 32 GB | 32 GB+ |
| 磁盘 | 60 GB（权重 16 GB + 环境） | 100 GB |
| 系统 | Ubuntu 22.04 + NVIDIA 驱动 | 同左 |

华为云「GPU 加速型」实例按需计费、按小时结算，**跑完就释放**。
一次完整实验（下载权重 + 跑 3~10 段录音 + 出表）通常 2~4 小时。

## 三、部署步骤

### 1. 装环境

```bash
sudo apt update && sudo apt install -y python3-pip ffmpeg
pip3 install --upgrade pip
pip3 install torch --index-url https://download.pytorch.org/whl/cu121
pip3 install transformers soundfile numpy
```

### 2. 拿权重（二选一）

**A. 在 GPU 机器上直接拉**（推荐，省掉 16 GB 上传）

```bash
pip3 install modelscope
python3 -c "
from modelscope import snapshot_download
p = snapshot_download('JimmyMa99/AntiFraud-SFT', cache_dir='/root/models')
print(p)
"
```

> ModelScope 上 `JimmyMa99/AntiFraud-SFT` 存在且可访问（本机已核实返回 200）。
> 拉完先确认文件齐全：`config.json`、`preprocessor_config.json`、
> `model-*.safetensors`（多个分片）、`vocab.json`、`merges.txt`。
> 缺 `preprocessor_config.json` 会加载失败——那是音频处理器配置。

**B. 从本机传**（已有 `D:\models\AntiFraud-SFT`，16 GB）

```bash
tar -czf antifraud-sft.tar.gz -C /d/models AntiFraud-SFT
# 16 GB 按 3 MB/s 约 90 分钟，带宽不够就选 A
scp antifraud-sft.tar.gz root@<GPU机器IP>:/root/models/
```

### 3. 传服务代码

只需要一个文件：

```bash
scp D:/华为ict/交付_银盾_GitHub/tools/sft_server.py root@<IP>:/root/
```

### 4. 起服务

```bash
# 模型目录用环境变量指过去
ANTIFRAUD_DIR=/root/models/AntiFraud-SFT python3 sft_server.py --host 0.0.0.0 --port 8099
```

首次加载约 2~5 分钟（GPU 上快得多）。探活：

```bash
curl http://127.0.0.1:8099/health
# 期望：{"ok": true, "model": "AntiFraud-SFT", "runs": 0, ...}
```

### 5. 跑一段录音验一下

```bash
python3 - <<'PY'
import base64, json, urllib.request
b = base64.b64encode(open('/root/13900001111_20261109103015.mp3','rb').read()).decode()
req = urllib.request.Request('http://127.0.0.1:8099/risk',
    data=json.dumps({'audio_b64': b, 'name': 'call.mp3'}).encode(),
    headers={'Content-Type': 'application/json'})
print(urllib.request.urlopen(req, timeout=300).read().decode())
PY
```

期望返回 `{"ok":true,"mode":"audio","durSec":42.5,"ms":...,"p":0.xx,"verdict":"..."}`。

### 6. 接进银盾链路（可选）

如果要在演示时让子女端看到第三票，把地址填进云端环境变量：

```bash
# cloud/.env
SFT_URL=http://<GPU机器内网IP>:8099
SFT_TIMEOUT=120000
SFT_WAIT_MS=60000
```

`ms` 是单条耗时。**如果超过 60 秒，`/api/callaudio` 会按超时处理**（第三票缺席，
结论回退到规则 + DeepSeek），这是设计好的降级，不是故障。

> 只在演示窗口内保持这台机器开着。实验做完立刻释放实例，不要把长期凭证写在机器上。

## 四、只要数字、不想部署成服务

如果目的只是「报告里有一组专用模型的对比数据」，**不用起 HTTP 服务**。
在 GPU 机器上写个循环，把 `demo/calls/*.mp3` 挨个跑一遍，存成 JSON 拿回来即可：

```bash
python3 - <<'PY'
import base64, glob, json, time, urllib.request
out = []
for p in sorted(glob.glob('/root/calls/*.mp3')):
    b = base64.b64encode(open(p,'rb').read()).decode()
    t = time.time()
    req = urllib.request.Request('http://127.0.0.1:8099/risk',
        data=json.dumps({'audio_b64': b}).encode(),
        headers={'Content-Type': 'application/json'})
    r = json.loads(urllib.request.urlopen(req, timeout=600).read())
    r['file'] = p.split('/')[-1]; r['wallSec'] = round(time.time()-t, 1)
    out.append(r); print(r)
json.dump(out, open('/root/sft-results.json','w'), ensure_ascii=False, indent=1)
PY
```

把 `sft-results.json` 拿回来，和 `cloud/test/out-llm.json`、`out-rules.json` 并排放进
`docs/模型对比实验.md`。

## 五、跑完要记的三件事

1. **单条耗时**（`ms`）——这是能不能进实时链路的关键数字
2. **显存峰值**——决定下次要租什么规格
3. **三路分歧**——SFT 判诈骗、DeepSeek 判正常的样本，这才是老师要的案例

前两项由服务自己报，第三项要人工比对。
