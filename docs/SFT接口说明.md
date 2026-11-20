# AntiFraud-SFT 接口说明（学校 GPU）

> 部署过程和环境要求见 [SFT 部署](SFT部署.md)。本文只讲**怎么调用**。
> 实测日期：2026-11-18，下面所有返回示例都是真实请求的结果。

## 一、地址与可用范围

| 项 | 值 |
|---|---|
| 地址 | `http://10.8.110.32:44855` |
| 可访问范围 | **仅校园网**（10.x 内网地址，线上 ECS 访问不到） |
| 鉴权 | **无**。不要把地址发到群外或公开文档 |
| 运行位置 | 学校 AI 实训平台（10.8.110.3）→ 实训工具 → 容器，镜像 `cuda-cudnn全装好` |
| 硬件 | NVIDIA L40（46 GB），实际占用约 17.8 GB 显存；容器配置 16 核 / 64 GB 内存 |

⚠️ **地址不是固定的。** `44855` 是平台给容器分配的外放端口（对应容器内 8000）。
容器关掉重开后端口可能变化，到平台容器页右侧「外放端口：port-8000:xxxxx」查看新值，
然后同步改 `cloud/.env` 的 `SFT_URL`。

## 二、接口一览

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/health` | 探活，看模型是否加载完成 |
| POST | `/judge` | **推荐。** 按模型训练时的原生问法判定，返回是否诈骗、理由、场景（5~60 秒） |
| POST | `/risk` | 旧接口，只比较第一个字「是」「否」的概率（0.15 秒）。**结果不可信，仅为兼容保留** |

其他路径一律返回 404：`{"ok": false, "msg": "只有 /health、/judge 和 /risk"}`。

> ⚠️ **2026-11-20 更正：不要再用 `/risk` 的 p 做判定。**
> 这个模型是按「先判通话场景 → 写一段分析 → 输出 `{"reason","confidence","is_fraud"}`」训练的
> （TeleAntiFraud 仓库 `example/`、`evaluation/`），从没被训练成「只答一个字」。
> `/risk` 强行让它只答一个字、比较第一个 token 的概率，这个数和输入内容关系很弱：
> 5 秒静音 p=0.62、440Hz 正弦波 p=0.85、物业停水 p=0.71，都判成诈骗。
> 学生 11-19 复核里「模型对任何输入都答是」的现象就是这么来的，**不是权重坏了**。
> 第四节以后的 `/risk` 说明只作历史记录。

## 三、GET /health

```bash
curl http://10.8.110.32:44855/health
```

```json
{"ok": true, "model": "AntiFraud-SFT", "dir": "/home/zkpk/models/JimmyMa99/AntiFraud-SFT",
 "loading": false, "error": null, "loadMs": 8451, "runs": 0}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `ok` | bool | `true` = 模型已就绪，可以调 `/risk` |
| `loading` | bool | `true` = 正在加载（服务刚启动，GPU 上约 9 秒） |
| `error` | string/null | 加载失败时的原因 |
| `loadMs` | int | 加载耗时（毫秒） |
| `runs` | int | 本次启动以来成功判定的次数 |

## 三之二、POST /judge（推荐）

请求体和 `/risk` 一样（`audio_b64` 或 `text` 二选一），可加 `"debug": true` 拿到模型的完整分析原文。

服务内部做两轮对话，和训练数据一致：

1. 第一轮：听音频（或读转写），在 7 类场景（订餐、咨询客服、预约、交通、购物、打车、外卖）里判一个；
2. 第二轮：结合第一轮的场景写分析，最后输出 `{"reason", "confidence", "is_fraud"}`；
3. 模型偶尔写完分析就停、不给 JSON（holdout 里 4/35），服务会把分析当开头让它补写 JSON。

**先过滤空输入**：音频有声部分不足 1 秒、文字不足 15 字，直接返回 `skipped: true`，不送模型。
原因是实测静音会被模型编出一段「找餐厅」的对话，再判成诈骗。

```bash
curl -X POST http://10.8.110.32:44855/judge -H 'Content-Type: application/json' \
  -d '{"text":"喂，阿姨您好，我是小区物业的小张。哎，你好你好。跟您说一下，明天上午九点到十一点小区停水检修，您提前接点水。好的，知道了。还有您家的快递放门卫了，有空下来拿一下。谢谢阿姨。"}'
```

```json
{"ok": true, "mode": "text", "chars": 86, "ms": 3393,
 "is_fraud": false, "verdict": "normal", "p": 0.1, "confidence": 0.9,
 "reason": "通话内容为常规物业服务通知，未涉及敏感信息索取或异常行为引导，符合正常服务沟通特征。",
 "scene": "预约服务", "parsedFrom": "json", "tokens": 118}
```

| 字段 | 含义 |
|---|---|
| `is_fraud` | `true` / `false`；解析不出来时为 `null` |
| `verdict` | `fraud` / `normal` / `unknown`（被过滤或解析失败） |
| `p` | 换算成「是诈骗」的概率：判诈骗时取 confidence，判正常时取 1 − confidence。模型给的 confidence 只有 0.85、0.9、0.98 这几档，**不要拿它细调阈值** |
| `reason` | 模型给的判定理由，可以直接展示给子女端 |
| `scene` | 第一轮判的场景 |
| `skipped` | `true` 表示输入太短被过滤，`reason` 里写了原因 |
| `parsedFrom` | `json` = 从 JSON 解析；`text` = 从分析文字里找到的 is_fraud；`none` = 没解析出来 |
| `ms` / `tokens` | 耗时、两轮共生成多少 token。**GPU 上 3~60 秒，中位约 4~12 秒** |

### 实测（2026-11-20，学校 L40，同一批输入）

| 输入 | 条数 | `/judge` 判对 | 旧 `/risk` |
|---|---|---|---|
| demo 三段录音（音频） | 正常 1 / 诈骗 2 | **3 / 3** | 物业停水误报 |
| 5 秒静音 | 1 | 被过滤 ✅ | p=0.62，判诈骗 |
| `holdout.json` 诈骗（文本） | 20 | **20 / 20** | — |
| `holdout.json` 正常（文本） | 15 | **9 / 15**（误报 6） | 学生实测误报 14/15 |
| CGoDial 真实政务热线（ASR 文本，随机 30 通） | 30 | **3 / 30**（误报 27） | — |

结论：

- 改成原生问法后，**模型确实会判「不是诈骗」**，短通知类正常通话明显好转
- 但**机构主动来电、要核实身份证、公积金医保这类话题，文本模式仍然大量误报**。
  它的理由几乎都是「未明确说明机构身份」「要求提供个人信息」——训练数据里这类正常通话太少
- 所以**还不能单独用来判定**，融合规则仍然只让它抬分、需要第二票。
  这批误报正好是 [SFT 下一步计划](SFT下一步计划.md) 第 3、4 步要补的负样本
- 模型是用音频训练的，文本模式本身就偏离了训练分布。有录音时优先传音频

## 四、POST /risk（旧接口，仅兼容）

请求头 `Content-Type: application/json`，请求体是 JSON，**`audio_b64` 和 `text` 至少给一个**；
两个都给时按音频判。

### 4.1 请求字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `audio_b64` | string | 二选一 | 录音文件的**原始字节**做 base64（不要带 `data:audio/...;base64,` 前缀） |
| `name` | string | 否 | 文件名，仅用于日志 |
| `text` | string | 二选一 | 通话转写文字。云端 `Sft.js` 会截到 6000 字以内 |

**音频要求：**

- 格式：**mp3 / wav / flac / ogg**（服务用 soundfile 解码）。
  **m4a / aac / amr 解不开**，会返回 400，需要先转成 mp3 或 wav
- 大小：解码前 ≤ 12 MB，整个请求体 ≤ 20 MB
- 采样率、声道不限：服务内部统一转成 16 kHz 单声道；电话录音的 8 kHz 没问题
- 时长：模型只看前约 30 秒（Qwen2-Audio 音频编码器的上限），更长的部分不参与判断。
  长通话想全覆盖，就自己切段多次调用，或者用文本模式传整段转写

### 4.2 响应字段（成功，HTTP 200）

| 字段 | 类型 | 含义 |
|---|---|---|
| `ok` | bool | `true` |
| `p` | float | **是诈骗的概率**，0~1，保留 4 位小数 |
| `verdict` | string | `"fraud"`（p ≥ 0.5）或 `"normal"` |
| `ms` | int | 模型推理耗时（毫秒），不含网络传输 |
| `mode` | string | `"audio"` 或 `"text"` |
| `srcRate` | int | 仅音频：原始采样率 |
| `durSec` | float | 仅音频：录音时长（秒） |
| `chars` | int | 仅文本：文字长度 |

`p` 的算法：让模型回答「是不是诈骗，只答是或否」，只做一次前向，
比较「是」「否」两个 token 的概率后归一化。没有生成过程，所以很快。

### 4.3 失败

| HTTP | 场景 | 示例 |
|---|---|---|
| 400 | 两个字段都没给 | `{"ok": false, "msg": "ValueError: audio_b64 和 text 至少给一个"}` |
| 400 | 音频解不开、超过 12 MB、请求体大小不对 | `{"ok": false, "msg": "<异常类型>: <原因>"}` |
| 503 | 模型还在加载或加载失败 | `{"ok": false, "msg": "模型还没加载好"}` |

所有响应都是 JSON，失败时 `ok` 为 `false`，原因在 `msg` 里。

### 4.4 调用示例

**curl（文本）**

```bash
curl -X POST http://10.8.110.32:44855/risk \
  -H 'Content-Type: application/json' \
  -d '{"text":"喂，您好，我是市公安局的，您名下银行卡涉嫌洗钱"}'
```

**curl（音频）**

```bash
printf '{"audio_b64":"%s","name":"call.mp3"}' "$(base64 < call.mp3 | tr -d '\n')" \
  | curl -X POST http://10.8.110.32:44855/risk -H 'Content-Type: application/json' -d @-
```

**Python**

```python
import base64, json, urllib.request

def sft_risk(path=None, text=None, url="http://10.8.110.32:44855/risk"):
    body = {"text": text} if text else {
        "audio_b64": base64.b64encode(open(path, "rb").read()).decode(),
        "name": path,
    }
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=120).read())

print(sft_risk("demo/calls/13900001111_20261109103015.mp3"))
```

**Node（银盾云端已封装好）**：`cloud/src/Sft.js` 的 `riskAudio(buffer)` / `riskText(text)`，
不需要自己拼请求。

## 五、接进银盾云端

在**校园网内**运行的云端，`cloud/.env` 里填：

```bash
SFT_URL=http://10.8.110.32:44855
SFT_TIMEOUT=120000   # 单次请求超时（毫秒）
SFT_WAIT_MS=60000    # 通话复盘最多等第三票多久，超时就只用规则 + DeepSeek
```

`/risk` 单次 0.15 秒；改用 `/judge` 后单次 3~60 秒，`SFT_WAIT_MS` 至少要 60000。

填好后用 `GET /api/callaudio/ping` 检查，`sft.enabled` 应为 `true`。
融合规则不变：**SFT 只能抬高风险，不能单独降低风险**（见 `CallAudio.js`）。

线上 ECS（123.249.25.139）在校外，访问不到这个地址，所以线上环境保持 `SFT_URL` 留空。

## 六、实测数据（2026-11-18）

样本：`demo/calls/` 下 3 段电话录音，以及对应的 `.txt` 转写稿。请求从校园网内的电脑发出。

| 录音 | 实际 | 时长 | 音频 p | 音频耗时 | 文本 p | 文本耗时 |
|---|---|---|---|---|---|---|
| 物业通知停水 | 正常 | 20.1 s | 0.7058 ❌ | 463 ms* | 0.7311 ❌ | 46 ms |
| 冒充孙子要钱 | 诈骗 | 28.8 s | 0.9993 ✅ | 148 ms | 0.9983 ✅ | 34 ms |
| 冒充公安洗钱 | 诈骗 | 42.5 s | 0.9972 ✅ | 148 ms | 0.9959 ✅ | 36 ms |

\* 服务重启后的第一次请求，含 GPU 预热；之后稳定在 150 ms 左右。

- 两段诈骗都判对，p 接近 1
- **物业通知被误报**，音频和文本两种模式都判成诈骗（p≈0.71~0.73）。
  这和规则引擎「陌生正常」类误报的问题一样（见 [数据集说明](数据集说明.md) 第四节）。
  这也正是 SFT 只能抬分、不能单独判定的原因
- 只有 3 条样本，**不能拿来算准确率**。要进报告，需要用 `cloud/test/holdout.json`
  的文本跑文本模式，并补一批真实录音

## 七、运维

服务跑在容器里（用户 `zkpk`），相关文件：

| 路径 | 内容 |
|---|---|
| `~/yindun/tools/sft_server.py` | 服务代码（GitHub 仓库的克隆） |
| `~/models/JimmyMa99/AntiFraud-SFT` | 模型权重，16 GB，从 ModelScope 下载 |
| `~/sft.log` | 服务日志 |
| `~/sft-results.json` | 三段样例的测试结果 |

在平台的远程桌面里打开终端：

```bash
# 查看状态
curl -s 127.0.0.1:8000/health; tail -n 20 ~/sft.log

# 重启服务（脚本在容器里，内容见下）
~/start_sft.sh
```

`~/start_sft.sh`：

```bash
#!/bin/bash
for pid in $(pgrep -f "^python3 tools/sft_server.py"); do kill $pid; done
sleep 3
cd ~/yindun && SFT_DEVICE=cuda ANTIFRAUD_DIR=$HOME/models/JimmyMa99/AntiFraud-SFT \
  setsid nohup python3 tools/sft_server.py --host 0.0.0.0 --port 8000 > ~/sft.log 2>&1 < /dev/null &
```

⚠️ **不要通过 SSH 远程执行 `pkill -f sft_server.py`**：远程命令行本身就含有这个字符串，
会把你自己的 SSH 会话一起杀掉，后面启动服务的命令就不会执行（11-20 踩过）。

- `SFT_DEVICE=cuda` 必须设置，不设就跑在 CPU 上（默认值，为兼容本机运行）
- 容器环境：Python 3.10、torch 2.3.1+cu121、transformers 4.4x（安装时限定 `>=4.45,<4.50`）。
  ModelScope 要用 `<1.30` 的版本，新版有 `HubConfig._logged_out` 的 bug，下载会直接报错
- **不用时在平台上关闭容器**，把 GPU 让给其他人。
  ⚠️ **已实测：「删除实验」后容器磁盘会清空**（权重、代码、训练结果全没了）。重建后要重新下载权重（约 10 分钟），
  重要的东西删除前先拷出来。SSH 登录方法见 [GPU 容器 SSH 使用说明](GPU容器SSH使用说明.md)
