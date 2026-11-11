# 华为云 SIS 接入记录

> 2026-11-11 首次用真实账号跑通「一句话识别」。这篇只记**踩过的坑和实测出来的数字**，
> 不重复官方文档 —— 下次换账号、换区域、或者答辩被问到"你们怎么接的华为云"，看这一页就够。

---

## 一、结论先说

| 项 | 值 / 做法 |
|---|---|
| 服务 | 语音交互服务 SIS → **一句话识别**（short-audio，同步接口） |
| 区域 | **华北-北京四 `cn-north-4`**（SIS 只在上海一和北京四两个区开得通） |
| 接口 | `POST https://sis-ext.cn-north-4.myhuaweicloud.com/v1/{project_id}/asr/short-audio` |
| 鉴权 | **AK/SK 签名（SDK-HMAC-SHA256）**，自己实现，`cloud/src/HwSign.js` |
| 参数 | `property = chinese_8k_common`（电话 8k 场景） |
| 实测延迟 | 42 秒录音 1.4~2.2 秒；128 秒切 3 段共产 7.0 秒 |
| 实测质量 | 与人工写的演示转写稿基本逐字吻合 |

---

## 二、四个坑（按踩到的顺序）

### 坑 1：开了登录保护的 IAM 用户，用不了密码换 token

现象：`POST https://iam.myhuaweicloud.com/v3/auth/tokens` 一直返回

```json
{"error":{"code":401,"message":"The username or password is wrong."}}
```

而密码是对的（控制台能登）。**报错文案是假的** —— 只要该 IAM 用户开了
「登录保护」（虚拟 MFA / 手机 / 邮件二次验证），华为云就不接受纯密码认证，
但它故意回"用户名或密码错误"，防止有人拿这个接口撞库。

判断办法：控制台「统一身份认证 → 用户 → 该用户 → 安全设置」，
看「登录保护」是不是"已开启"。开了就**别在这条路上耗**。

另外还有个连带坑：连续失败会导致账号锁定 15 分钟，**锁定期间的报错和密码错误一模一样**，
所以越试越像密码错，越试越锁死。

### 坑 2：账号名（domain）和 IAM 用户名（user）是两个字段

`auth.identity.password.user.name` 要 **IAM 用户名**，
`auth.identity.password.user.domain.name` 要 **账号名** —— 两者常常长得完全不一样
（本例：用户名 `zhuziyuan`，账号名 `hid_c7n0rhm2aeqwlq`）。
填反了同样返回 401，同样报"密码错误"。

### 坑 3：自己实现签名，CanonicalURI 末尾要补一个 `/`

换成 AK/SK 后能到网关，但一直：

```json
{"error_code":"APIG.0301","error_msg":"Incorrect IAM authentication information: verify ak sk signature fail"}
```

原因：华为云官方 SDK 的 `CanonicalURI()` 在拼完路径后有这么一行

```javascript
if (urlpath[urlpath.length - 1] !== '/') {
    urlpath = urlpath + '/';   // ← 少这一个字符，签名必挂
}
```

也就是说实际请求 `/v1/{pid}/asr/short-audio`，**参与签名的是 `/v1/{pid}/asr/short-audio/`**。

定位办法（推荐沿用）：`npm i @huaweicloud/huaweicloud-sdk-core`，
拿它的 `AKSKSigner.sign()` 和自研实现对**同一个请求**各算一次，
两个 `Authorization` 头逐字符比对，差在哪一目了然。我们比对后达到完全一致。

### 坑 4：`property` 写错也报 400，但报错码不一样

```json
{"error_code":"SIS.0032","error_msg":"'property' is invalid"}
```

`chinese_8k_general` 这个名字在「一句话识别」里**不存在**（那是实时语音识别的取值）。
一句话识别可用 `chinese_8k_common`（电话 8k）、`chinese_16k_common` 等。
实测 `chinese_8k_common` / `chinese_16k_common` / `chinese_16k_general` 三个都能跑通，
8k 素材用 `chinese_8k_common` 最贴合。

---

## 三、60 秒限制：实测边界与我们的做法

### 实测

| 音频 | 大小 | 结果 |
|---|---|---|
| 42.8 秒 mp3（8k / 24 kbps CBR） | 128 KB | ✅ 正常识别 |
| 128.3 秒 mp3 | 376 KB | ❌ `SIS.0032 data element too large` |

官方文档写"≤ 60 秒"，实测 128 秒被拒、42 秒正常，中间没再细分 ——
**结论是限制真实存在，必须处理**。

### 做法：云端按帧切段（不改 App）

`cloud/src/AudioSplit.js`：

- **mp3**：解析帧头（位率表 / 采样率表 / 层号），累加每帧时长，到 55 秒就在帧边界下刀。
  切出来的每段都是合法可播的 MP3，不重新编码。
- **amr / awb**：每帧固定 20 ms，段首补 `#!AMR` 文件头即可独立识别。
- **wav**：按 blockAlign 对齐切 PCM，每段重写 RIFF/data 长度字段。
- **m4a / aac / 3gp / ogg**：容器带全局索引，纯字节切割会切坏 → 返回 null 整段送，
  失败时明确报"该格式无法无损切段，请改用 mp3"，不假装处理成功。

`Asr.viaSisChunked()` 负责调用：切 → 逐段识别 → 拼接（不加分隔符，SIS 已带标点）。
默认 55 秒，可用 `SIS_CHUNK_SEC` 调整。

### 实测效果

128 秒合成录音 → 切 **3 段（54.9 / 54.9 / 18.4 秒）** → 三段全部识别成功 →
拼回 559 字，内容连贯，无整句丢失。接口返回里如实带 `asr.parts=3`、`asr.durationSec=128`。

### 一个必须记下来的连带影响

切段后转写稿从 200 字级涨到 500~1000 字，**大模型判定的耗时也跟着涨**：
实测 559 字的通话稿在 `MAAS_TIMEOUT=20000` 下超时（日志 `[Pangu] judgeCall 失败：请求超时`）。
已调到 40 秒。超时不会让整次复盘失败，会自动退回纯规则判定（`basis=rule`），这条降级路径实测有效。

> 也就是说：**语音这边放宽了时限，判定那边就得多给时间**，
> 两个超时要一起看，不能只调一个。

---

## 四、怎么验证（换环境时照着跑）

```bash
cd cloud
node test/sis-ping.js        # 配置自检 + 三段真实录音识别对比
```

脚本会分两层报错，能直接定位是"认证没过"还是"业务参数不对"：
① 鉴权（IAM token 或 AK/SK）→ ② short-audio 接口。

`GET /api/callaudio/ping` 会返回 `asr: "sis"` 表示已走真实识别；
`/api/health` 里能看到模型和存储的降级状态。

---

## 五、成本

一句话识别按次计费（按音频时长），本项用量极小：一段 2 分钟通话 = 3 次调用。
账户余额 ¥4.73 时实测几十次调用无明显消耗，够演示和答辩用。
