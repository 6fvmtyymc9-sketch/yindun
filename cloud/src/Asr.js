'use strict';
/**
 * ============================================================================
 *  Asr.js —— 通话录音转文字
 * ----------------------------------------------------------------------------
 *  三种来源，按顺序选第一个可用的：
 *
 *   1. sis     华为云 SIS 一句话识别（短音频，≤ 60 秒）。
 *              配 SIS_PROJECT_ID + （SIS_TOKEN 或 IAM 账号密码）即启用。
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

class Asr {
  constructor(env = process.env) {
    this.sis = {
      region: env.SIS_REGION || 'cn-north-4',
      projectId: env.SIS_PROJECT_ID || '',
      token: env.SIS_TOKEN || '',
      user: env.HW_IAM_USER || '',
      pass: env.HW_IAM_PASS || '',
      domain: env.HW_IAM_DOMAIN || '',
      // 通话录音一般是 8k 采样。属性名以华为云控制台文档为准，可用环境变量改
      property: env.SIS_PROPERTY || 'chinese_8k_general',
      tokenAt: 0,
    };
    this.openai = {
      url: env.ASR_URL || '',
      key: env.ASR_KEY || '',
      model: env.ASR_MODEL || 'whisper-1',
    };
    this.timeout = Number(env.ASR_TIMEOUT || 30000);
  }

  get sisEnabled() {
    return !!(this.sis.projectId && (this.sis.token || (this.sis.user && this.sis.pass && this.sis.domain)));
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
   * @param {{audio:Buffer, name:string, demoText?:string}} input
   * @returns {Promise<{text:string, source:string}>}
   */
  async transcribe({ audio, name, demoText }) {
    if (this.sisEnabled) return { text: await this.viaSis(audio, name), source: 'sis' };
    if (this.openaiEnabled) return { text: await this.viaOpenai(audio, name), source: 'openai' };
    if (demoText && demoText.trim()) return { text: demoText.trim(), source: 'demo' };
    const err = new Error('没有配置语音识别（SIS_* 或 ASR_*），录音旁边也没有演示转写稿');
    err.status = 503;
    throw err;
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
    const body = {
      config: { audio_format: formatOf(name) || 'mp3', property: s.property, add_punc: 'yes' },
      data: audio.toString('base64'),
    };
    const resp = await fetchWithTimeout(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Auth-Token': await this.sisToken() },
      body: JSON.stringify(body),
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
