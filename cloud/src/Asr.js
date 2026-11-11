'use strict';
/**
 * ============================================================================
 *  Asr.js —— 通话录音转文字
 * ----------------------------------------------------------------------------
 *  三种来源，按顺序选第一个可用的：
 *
 *   1. sis     华为云 SIS 一句话识别（单次短音频）。
 *              配 SIS_PROJECT_ID + 鉴权（AK/SK 优先，其次 SIS_TOKEN，再次 IAM 账号密码）即启用。
 *              超过 SIS_CHUNK_SEC（默认 55 秒）的录音会自动按帧切段、逐段识别再拼接，
 *              见 AudioSplit.js 与 viaSisChunked()。
 *   2. openai  任意 OpenAI 兼容的 /audio/transcriptions 接口（Whisper 类）。
 *              配 ASR_URL + ASR_KEY 即启用。
 *   3. demo    演示转写稿：手机端在录音旁边找到同名 .txt，一起传上来。
 *              只在前两种都没配置时才用，结果里会标明 source=demo，
 *              子女端卡片上也会写"转写来源：演示文本"，不冒充真识别。
 *
 *  注意：sis / openai 两种接口在开发时没有真实账号可测，
 *  第一次接入时请先用 /api/callaudio/ping 自检。
 * ============================================================================
 */

/** 文件扩展名 → 音频格式 */
function formatOf(name) {
  const m = String(name || '').toLowerCase().match(/\.([a-z0-9]+)$/);
  return m ? m[1] : '';
}

const MIME = {
  mp3: 'audio/mpeg', m4a: 'audio/mp4', aac: 'audio/aac', amr: 'audio/amr',
  wav: 'audio/wav', '3gp': 'audio/3gpp', ogg: 'audio/ogg', awb: 'audio/amr-wb',
};

const { signRequest } = require('./HwSign');
const { split } = require('./AudioSplit');

class Asr {
  constructor(env = process.env) {
    this.sis = {
      region: env.SIS_REGION || 'cn-north-4',
      projectId: env.SIS_PROJECT_ID || '',
      token: env.SIS_TOKEN || '',
      // AK/SK 优先：永不过期、不依赖密码（见 HwSign.js 顶部说明）
      ak: env.HW_AK || '',
      sk: env.HW_SK || '',
      user: env.HW_IAM_USER || '',
      pass: env.HW_IAM_PASS || '',
      domain: env.HW_IAM_DOMAIN || '',
      // 通话录音一般是 8k 采样。属性名以华为云控制台文档为准，可用环境变量改
      property: env.SIS_PROPERTY || 'chinese_8k_common',
      tokenAt: 0,
    };
    this.openai = {
      url: env.ASR_URL || '',
      key: env.ASR_KEY || '',
      model: env.ASR_MODEL || 'whisper-1',
    };
    // 切段长度：默认 55 秒，给接口的 60 秒限制留余量（实测 128 秒会被拒）
    this.sisChunkSec = Number(env.SIS_CHUNK_SEC || 55);
    this.timeout = Number(env.ASR_TIMEOUT || 30000);
  }

  /** 有没有可用的凭证（AK/SK 优先，其次现成 token，最后 IAM 账号密码） */
  get sisEnabled() {
    const s = this.sis;
    return !!(
      s.projectId &&
      ((s.ak && s.sk) ||
        s.token ||
        (s.user && s.pass && s.domain))
    );
  }

  /** 当前用哪种鉴权，给日志和 ping 看 */
  get sisAuth() {
    const s = this.sis;
    if (s.ak && s.sk) return 'aksk';
    if (s.token) return 'token';
    if (s.user && s.pass && s.domain) return 'iam';
    return 'none';
  }

  get openaiEnabled() {
    return !!(this.openai.url && this.openai.key);
  }

  /** 当前会用哪一种（给 /api/health 和日志看） */
  get mode() {
    if (this.sisEnabled) return 'sis';
    if (this.openaiEnabled) return 'openai';
    return 'demo';
  }

  /**
   * 转文字。
   * @param {{audio:Buffer, name:string, demoText?:string, maxSec?:number}} input
   * @returns {Promise<{text:string, source:string, parts?:number, durationSec?:number}>}
   */
  async transcribe({ audio, name, demoText, maxSec }) {
    if (this.sisEnabled) {
      const out = await this.viaSisChunked(audio, name, maxSec);
      return { text: out.text, source: 'sis', parts: out.parts, durationSec: out.durationSec };
    }
    if (this.openaiEnabled) return { text: await this.viaOpenai(audio, name), source: 'openai' };
    if (demoText && demoText.trim()) return { text: demoText.trim(), source: 'demo' };
    const err = new Error('没有配置语音识别（SIS_* 或 ASR_*），录音旁边也没有演示转写稿');
    err.status = 503;
    throw err;
  }

  /**
   * 一句话识别：长录音自动切段。
   *
   * 【为什么必须切】SIS 的 short-audio 接口单次吃不下长音频 —— 2026-11-11 实测：
   *   128 秒 / 376 KB 的 mp3 直接送，返回 SIS.0032 "data element too large"。
   *   而诈骗电话往往 2~5 分钟，"把钱转到安全账户"这类要害句经常在最后一段。
   *   如果不切，只识别前一段，等于把最关键的证据丢掉 —— 这比识别不出来更糟。
   *
   * 【切多长】默认 55 秒，留 5 秒余量：接口的限制是按秒算的，切太满容易在
   *   帧边界上刚好超一点。可用 SIS_CHUNK_SEC 调整。
   *
   * 【切不动怎么办】m4a/aac/3gp/ogg 这些容器格式带全局索引，纯字节切割会切坏，
   *   AudioSplit 直接返回 null。这时按整段送，失败了就如实报错并提示原因，
   *   不假装"已经处理好了"。
   */
  async viaSisChunked(audio, name, maxSec) {
    const cutSec = Number(maxSec || this.sisChunkSec) || 55;
    let cut = null;
    try {
      cut = split(audio, name, cutSec);
    } catch (e) {
      console.warn('[Asr] 切段解析失败，按整段送：', e.message);
    }

    if (!cut || cut.parts.length <= 1) {
      try {
        const text = await this.viaSis(audio, name);
        return { text, parts: 1, durationSec: cut ? cut.durationSec : 0 };
      } catch (e) {
        if (/too large/i.test(e.message)) {
          const fmt = formatOf(name) || '未知';
          throw new Error(
            `录音超过一句话识别的单次上限，且 ${fmt} 格式无法无损切段` +
            `（只有 mp3 / amr / wav 支持按帧切割）。请改用 mp3 录音，或改用录音文件识别接口。`
          );
        }
        throw e;
      }
    }

    const texts = [];
    for (let i = 0; i < cut.parts.length; i += 1) {
      texts.push(await this.viaSis(cut.parts[i], name));
    }
    // 直接拼接：SIS 已经在每段末尾补了标点，再加分隔符反而多出顿号
    return { text: texts.join(''), parts: cut.parts.length, durationSec: cut.durationSec };
  }

  // --------------------------------------------------------------------------
  //  华为云 SIS 一句话识别
  // --------------------------------------------------------------------------
  async sisToken() {
    const s = this.sis;
    if (s.token && !s.user) return s.token;              // 直接给了 token
    if (s.token && Date.now() - s.tokenAt < 20 * 3600 * 1000) return s.token;  // token 有效期 24 小时
    const body = {
      auth: {
        identity: {
          methods: ['password'],
          password: { user: { name: s.user, password: s.pass, domain: { name: s.domain } } },
        },
        scope: { project: { name: s.region } },
      },
    };
    const resp = await fetchWithTimeout('https://iam.myhuaweicloud.com/v3/auth/tokens', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }, this.timeout);
    if (!resp.ok) throw httpError('IAM 取 token', resp.status, await resp.text().catch(() => ''));
    s.token = resp.headers.get('x-subject-token') || '';
    s.tokenAt = Date.now();
    if (!s.token) throw new Error('IAM 没有返回 X-Subject-Token');
    return s.token;
  }

  async viaSis(audio, name) {
    const s = this.sis;
    const url = `https://sis-ext.${s.region}.myhuaweicloud.com/v1/${s.projectId}/asr/short-audio`;
    // 先序列化成字符串再发：AK/SK 签名必须签在"真正发出去的那串字节"上，
    // 若签名时用对象、发送时再 JSON.stringify，两次结果可能不同导致 401。
    const payload = JSON.stringify({
      config: { audio_format: formatOf(name) || 'mp3', property: s.property, add_punc: 'yes' },
      data: audio.toString('base64'),
    });

    let headers = { 'Content-Type': 'application/json' };
    if (s.ak && s.sk) {
      headers = signRequest({ method: 'POST', url, headers, body: payload, ak: s.ak, sk: s.sk });
    } else {
      headers['X-Auth-Token'] = await this.sisToken();
    }

    const resp = await fetchWithTimeout(url, {
      method: 'POST',
      headers,
      body: payload,
    }, this.timeout);
    const txt = await resp.text().catch(() => '');
    if (!resp.ok) throw httpError('SIS 识别', resp.status, txt);
    const data = JSON.parse(txt);
    const text = data && data.result && data.result.text;
    if (!text) throw new Error('SIS 没有返回识别结果：' + txt.slice(0, 200));
    return String(text);
  }

  // --------------------------------------------------------------------------
  //  OpenAI 兼容的转写接口
  // --------------------------------------------------------------------------
  async viaOpenai(audio, name) {
    const o = this.openai;
    const fmt = formatOf(name);
    const form = new FormData();
    form.append('model', o.model);
    form.append('language', 'zh');
    form.append('file', new Blob([audio], { type: MIME[fmt] || 'application/octet-stream' }), name || 'call.mp3');
    const resp = await fetchWithTimeout(o.url, {
      method: 'POST',
      headers: { Authorization: `Bearer ${o.key}` },
      body: form,
    }, this.timeout);
    const txt = await resp.text().catch(() => '');
    if (!resp.ok) throw httpError('转写接口', resp.status, txt);
    const data = JSON.parse(txt);
    if (!data || typeof data.text !== 'string') throw new Error('转写接口没有返回 text：' + txt.slice(0, 200));
    return data.text;
  }
}

async function fetchWithTimeout(url, opt, ms) {
  const ctrl = new AbortController();
  const tid = setTimeout(() => ctrl.abort(), ms);
  try {
    return await fetch(url, { ...opt, signal: ctrl.signal });
  } catch (e) {
    throw new Error(e.name === 'AbortError' ? `请求超时（${ms}ms）` : e.message);
  } finally {
    clearTimeout(tid);
  }
}

function httpError(what, status, txt) {
  const err = new Error(`${what}失败：HTTP ${status} ${String(txt).slice(0, 200)}`);
  err.status = status;
  return err;
}

module.exports = { Asr, formatOf };
