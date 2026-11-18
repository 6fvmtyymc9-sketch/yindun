'use strict';
/**
 * ============================================================================
 *  Ocr.js —— 短信截图 → 文字（华为云文字识别 OCR 通用文字识别）
 * ----------------------------------------------------------------------------
 *  为什么需要它：
 *    老人不会打字，所以 App 里「上报可疑信息」这条路上，最自然的操作是**截个图**。
 *    截图本身没有文字，规则引擎（judge.scoreSms）读不了；以前是靠多模态大模型看图。
 *    2026-11 的改造把大模型从判定链上去掉了，图片就没人看了 —— 于是必须有 OCR
 *    把图变成字，后面才能接"规则 → SFT → 结论"这条线。
 *
 *  它在这一票里的位置：
 *    不是判定器，是**输入端的前置**。它只负责把像素变成文字，不下任何风险结论。
 *    判不判诈骗，是 Judge / Sft 的事。
 *
 *  为什么复用 HwSign：
 *    OCR 和 SIS 用的是同一套 AK/SK 与同一种签名（SDK-HMAC-SHA256），
 *    见 HwSign.js 顶部说明。不需要再申请一套新的密钥。
 *
 *  为什么 ping() 不发真请求：
 *    OCR 没有免费的 health 接口，每次识别都是计费的（每月前 1000 次免费）。
 *    /api/health 会被频繁轮询，如果它触发一次真实识别，免费额度很快烧完。
 *    所以这里只报"配没配 + 上一次调用的结果"，不主动花钱。
 *
 *  环境变量（都有默认值，只有 AK/SK 是必须的）：
 *    HW_AK / HW_SK      与 SIS 共用
 *    OCR_REGION         默认继承 SIS_REGION，再默认 cn-north-4
 *    OCR_ENDPOINT       接入点，留空用 ocr.{region}.myhuaweicloud.com
 *    OCR_PATH           默认 /v1.0/ocr/general-text
 *    OCR_TIMEOUT        毫秒，默认 15000
 *    OCR_MAX_B64        base64 字符串长度上限，默认 6MB（约 4.5MB 原图）
 *    OCR_EXTRA_JSON     额外请求参数（JSON 字符串），不写代码也能调接口行为
 * ============================================================================
 */

const { signRequest } = require('./HwSign');

class Ocr {
  constructor(env = process.env) {
    this.region = env.OCR_REGION || env.SIS_REGION || 'cn-north-4';
    this.endpoint = env.OCR_ENDPOINT || '';
    this.path = env.OCR_PATH || '/v1.0/ocr/general-text';
    this.ak = env.HW_AK || '';
    this.sk = env.HW_SK || '';
    this.timeout = Math.max(3000, Number(env.OCR_TIMEOUT || 15000));
    this.maxB64 = Math.max(64 * 1024, Number(env.OCR_MAX_B64 || 6 * 1024 * 1024));
    this.extra = null;
    if (env.OCR_EXTRA_JSON) {
      try {
        this.extra = JSON.parse(env.OCR_EXTRA_JSON);
      } catch (e) {
        console.warn('[Ocr] OCR_EXTRA_JSON 不是合法 JSON，已忽略：', e.message);
      }
    }
    /** 上一次调用的结果，给 /api/health 和 /api/report 的 meta 用 */
    this.last = { ok: null, ms: 0, chars: 0, err: null, at: 0 };
  }

  get enabled() {
    return !!(this.ak && this.sk);
  }

  get host() {
    return this.endpoint || `ocr.${this.region}.myhuaweicloud.com`;
  }

  /**
   * 识别一张图。
   * @param {{imageBase64:string, mime?:string}} input
   *        imageBase64 既接受纯 base64，也接受 data:image/jpeg;base64,... 这种 data URL
   * @returns {Promise<{text:string, sender:string[], ui:string[], chars:number, ms:number, blocks:number}>}
   *          失败会抛错，由调用方决定是报 4xx 还是退化成"只按文字判"。
   *          这里不吞异常 —— 因为"图没读出来"和"图读出来是空的"是两件事，
   *          前者要告诉用户"识别失败请重试"，后者要告诉用户"这张图里没看到字"。
   */
  async recognize({ imageBase64, mime }) {
    if (!this.enabled) {
      const e = new Error('未配置 OCR（缺 HW_AK / HW_SK）');
      e.code = 'OCR_NOT_CONFIGURED';
      throw e;
    }
    const b64 = stripDataUrl(imageBase64);
    if (!b64) {
      const e = new Error('图片内容为空');
      e.code = 'OCR_EMPTY';
      throw e;
    }
    if (b64.length > this.maxB64) {
      const e = new Error(
        `图片太大（base64 ${(b64.length / 1048576).toFixed(1)}MB，上限 ${(this.maxB64 / 1048576).toFixed(1)}MB），请压缩后再传`,
      );
      e.code = 'OCR_TOO_LARGE';
      throw e;
    }

    const url = `https://${this.host}${this.path}`;
    // 先序列化成字符串再发：AK/SK 签名必须签在"真正发出去的那串字节"上，
    // 若签名时用对象、发送时再 stringify，两次结果可能不同导致 401（SIS 踩过）。
    const payload = JSON.stringify({ image: b64, ...(this.extra || {}) });
    const headers = signRequest({
      method: 'POST',
      url,
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      ak: this.ak,
      sk: this.sk,
    });

    const t0 = Date.now();
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), this.timeout);
    let resp;
    let txt;
    try {
      resp = await fetch(url, { method: 'POST', headers, body: payload, signal: ac.signal });
      txt = await resp.text();
    } catch (e) {
      const msg = e.name === 'AbortError' ? `OCR 超时（${this.timeout}ms）` : e.message;
      this.last = { ok: false, ms: Date.now() - t0, chars: 0, err: msg, at: Date.now() };
      const err = new Error(msg);
      err.code = 'OCR_NETWORK';
      throw err;
    } finally {
      clearTimeout(timer);
    }

    const ms = Date.now() - t0;
    if (!resp.ok) {
      const msg = `OCR 失败：HTTP ${resp.status} ${String(txt).slice(0, 200)}${hintOf(resp.status, txt)}`;
      this.last = { ok: false, ms, chars: 0, err: msg, at: Date.now() };
      const err = new Error(msg);
      err.status = resp.status;
      err.code = 'OCR_HTTP';
      throw err;
    }

    let data;
    try {
      data = JSON.parse(txt);
    } catch (e) {
      const msg = 'OCR 返回的不是 JSON：' + String(txt).slice(0, 200);
      this.last = { ok: false, ms, chars: 0, err: msg, at: Date.now() };
      const err = new Error(msg);
      err.code = 'OCR_BAD_JSON';
      throw err;
    }

    const parsed = extractText(data);
    this.last = { ok: true, ms, chars: parsed.text.length, err: null, at: Date.now() };
    return { ...parsed, ms };
  }

  /**
   * 状态，给 /api/health 用。不发真请求（见文件头说明）。
   */
  status() {
    return {
      enabled: this.enabled,
      region: this.region,
      // 上一次调用的真实结果：没调过是 null，不要让运维误以为"已接入且健康"
      last: this.last.ok === null ? null : {
        ok: this.last.ok,
        ms: this.last.ms,
        chars: this.last.chars,
        err: this.last.err,
        at: this.last.at ? new Date(this.last.at).toISOString() : null,
      },
    };
  }
}

// ----------------------------------------------------------------------------
//  工具
// ----------------------------------------------------------------------------

/** 'data:image/jpeg;base64,xxx' → 'xxx'；纯 base64 原样返回 */
function stripDataUrl(s) {
  const str = String(s || '').trim();
  const i = str.indexOf(',');
  if (str.slice(0, 5).toLowerCase() === 'data:' && i > 0) return str.slice(i + 1);
  return str;
}

/**
 * 从各种可能的返回结构里把文字抠出来。
 *
 * 【为什么写这么松】华为云 OCR 有 v1.0 和 v2 两版接口，不同接口/不同时期返回的
 * 字段名不完全一致（words_block_list / words_result / text）。我们只关心"字是什么"，
 * 与其猜死一种结构、上线才发现解析不到，不如按已知几种都试一遍。
 * 将来真跑出来是别的结构，日志里会打出原始 JSON，改一处即可。
 */
function extractText(data) {
  const r = (data && (data.result || data)) || {};
  const out = [];

  // 结构一：{ result: { words_block_list: [{ words }] } }
  if (Array.isArray(r.words_block_list)) {
    for (const b of r.words_block_list) {
      if (b && typeof b.words === 'string' && b.words.trim()) out.push(b.words.trim());
    }
  }
  // 结构二：{ result: { words_result: [{ words }] } }
  if (!out.length && Array.isArray(r.words_result)) {
    for (const b of r.words_result) {
      const w = b && (typeof b.words === 'string' ? b.words : typeof b.text === 'string' ? b.text : '');
      if (w && w.trim()) out.push(w.trim());
    }
  }
  // 结构三：{ result: { text: '整段文字' } }
  if (!out.length && typeof r.text === 'string' && r.text.trim()) {
    out.push(r.text.trim());
  }

  const { text, sender, ui } = tidy(out);
  return { text, sender, ui, chars: text.length, blocks: out.length };
}

// ----------------------------------------------------------------------------
//  截图清洗
// ----------------------------------------------------------------------------

/** 屏幕上的界面元素，不是短信内容。留着会把状态栏噪声喂进规则 */
const UI_NOISE = [
  /^\d{1,2}:\d{2}$/, // 状态栏时间「9:41」
  /^((今天|昨天|前天|星期[一二三四五六日天]|\d{1,4}[-\/年]\d{1,2}[-\/月]\d{1,2}日?|\d{1,2}月\d{1,2}日)\s*)?\d{1,2}:\d{2}$/, // 「今天09:38」这类时间戳
  /^\d{1,3}\s*%$/, // 电量「82%」
  /^(5G|4G\+?|3G|2G|LTE|VoLTE|HD|Wi-?Fi|WLAN)$/i, // 信号标识
  /^[<‹《〈]?\s*(信息|短信|消息|返回|收件箱|全部已读|编辑)$/, // 标题栏
  /^(中国移动|中国联通|中国电信|China Mobile|China Unicom|China Telecom)$/i,
  /(未存储在通讯录|已存储在通讯录|存储为联系人)/, // 号码归属提示，不是正文
  /^[·•\-—_=~*.\s]+$/, // 分隔线
];

/** 发送者行：`+65 8123 4477`、`10086`、`95588` 这类纯号码 */
const PHONE_RE = /^\+?\d[\d\s\-()]{4,}$/;

/** 句读符号：行尾出现它说明这句写完了，下一行是新段落而不是折行 */
const SENTENCE_END = /[。！？!?…；;：:、，,）」』""'']$/;

/**
 * 把 OCR 的逐行输出整理成「像原文那样的一条短信」。
 *
 * 【为什么必须做，实测踩过】
 * 短信截图是自动折行的，OCR 逐行返回，断点常常落在词中间。实测一条冒充公检法的短信：
 *
 *      ...银行卡涉嫌一起洗
 *      钱案件，现已立案侦查...
 *
 * 「洗钱」被切成「洗」+「钱」，规则库里的关键词「洗钱」再也匹配不上，
 * 整条短信少 30 分、少一个 S2 命中（220 分 vs 250 分，都是 S4，但换成
 * 分数卡在临界点的样本就会漏报）。这个损失**不报错、不打日志**，
 * 只有拿 OCR 结果和原图逐字对照才看得出来。
 *
 * 做法：上一行末尾没有句读符号 → 判定为折行，直接拼接；有句读符号 → 换段。
 * 不用逗号补，避免凭空伪造标点影响后续判定。
 */
function tidy(lines) {
  const sender = [];
  const ui = [];
  const body = [];

  for (const raw of lines) {
    const s = String(raw).replace(/\s+/g, ' ').trim();
    if (!s) continue;
    if (UI_NOISE.some((re) => re.test(s))) {
      ui.push(s);
      continue;
    }
    if (PHONE_RE.test(s)) {
      sender.push(s); // 号码单独留出来：将来判定"境外来电/短信"要用
      continue;
    }
    body.push(s);
  }

  let text = '';
  for (const s of body) {
    if (!text) {
      text = s;
    } else if (SENTENCE_END.test(text)) {
      text += '\n' + s; // 上一句已收尾，这是新段落
    } else {
      text += s; // 上一句没收尾，是被折行切断的，接回去
    }
  }

  return { text, sender, ui };
}

/** 把 API 错误码翻译成"人能照着做的一句话"，原始报文里的错误码自己看不懂 */
function hintOf(status, txt) {
  const t = String(txt || '');
  if (status === 401 || /APIGW\.0301|verify ak sk/i.test(t)) {
    return '（AK/SK 签名不匹配：检查 HW_AK/HW_SK 是否和 SIS 用的是同一组，以及服务器时间是否准确）';
  }
  if (status === 403 || /not subscribed|not authorized|CBC\./i.test(t)) {
    return '（403：这个账号可能还没开通「文字识别 OCR」服务，或该区域未开通）';
  }
  if (status === 429) return '（429：调用被限流，OCR 默认每月 1000 次免费额度）';
  if (/image.*(too large|size)/i.test(t)) return '（图片超过接口大小限制，需要在上传前压缩）';
  return '';
}

module.exports = { Ocr, extractText, tidy };
