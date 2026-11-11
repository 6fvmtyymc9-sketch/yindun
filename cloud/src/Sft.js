'use strict';
/**
 * ============================================================================
 *  Sft.js —— 反诈专用模型 AntiFraud-SFT 的调用通道
 * ----------------------------------------------------------------------------
 *  为什么要有它：
 *    规则和 DeepSeek 看的都是**转写出来的文字**。转写丢掉的东西恰恰是骗子
 *    最容易露馅的地方——语速、语气、背景里那句"你按我说的做"。
 *    AntiFraud-SFT 是拿诈骗通话录音微调过的 Qwen2-Audio，它直接听音频。
 *
 *  它在这一票里的位置：
 *    不是替代 DeepSeek，是**第三票**。
 *      · 规则      —— 离线、毫秒级，负责守住底线
 *      · DeepSeek  —— 读懂"换了说法"，负责别漏
 *      · 本模型    —— 听声音，负责补文字丢掉的线索
 *    融合规则见 CallAudio.js：它能**抬**风险，不能单独**降**风险。
 *    理由：一条录音只喂进去几十秒，模型没听到要钱那一段就会判低分，
 *    让它单独否决，等于给骗子留了个后门。
 *
 *  为什么是可插拔的：
 *    模型 16 GB，要 PyTorch。ECS 只有 2 核 2 GB 且无 GPU，装不下。
 *    SFT_URL 留空 = 不启用，链路照常走规则 + DeepSeek，不报错、不降级。
 *    本机或以后有 GPU 的机器起 `tools/sft_server.py`，把地址填进来就能接。
 *
 *  调用约定（见 tools/sft_server.py）：
 *    GET  /health → {ok, loading, error, loadMs, runs}
 *    POST /risk   → {ok, p, verdict, ms, mode}
 *    p 是「是诈骗」的概率，0~1。
 * ============================================================================
 */

class Sft {
  constructor(env = process.env) {
    this.url = String(env.SFT_URL || '').replace(/\/+$/, '');
    this.model = 'AntiFraud-SFT';
    /** CPU 上一次前向几十秒，超时要给够；给不够会把好好的判定当失败 */
    this.timeout = Math.max(5000, Number(env.SFT_TIMEOUT || 120000));
  }

  get enabled() {
    return !!this.url;
  }

  async _post(path, body, timeout) {
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), timeout || this.timeout);
    try {
      const r = await fetch(this.url + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: ac.signal,
      });
      const txt = await r.text();
      if (!r.ok) throw new Error(`HTTP ${r.status} ${txt.slice(0, 160)}`);
      return JSON.parse(txt);
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * 听一段录音。
   * @param {{audio:Buffer, name?:string, mime?:string}} input
   * @returns {Promise<{p:number, verdict:string, ms:number, mode:string}|null>}
   *          失败一律返回 null —— 第三票缺席不该让整条链路失败。
   */
  async riskAudio({ audio, name, mime }) {
    if (!this.enabled || !audio || !audio.length) return null;
    try {
      const out = await this._post('/risk', {
        audio_b64: audio.toString('base64'),
        name: name || 'call.mp3',
        mime: mime || '',
      });
      if (!out || !out.ok || typeof out.p !== 'number') return null;
      return { p: out.p, verdict: out.verdict, ms: out.ms, mode: out.mode || 'audio' };
    } catch (e) {
      console.warn('[Sft] 音频判定失败（按缺席处理）：', e.message);
      return null;
    }
  }

  /** 只有转写稿时也能问一嘴，但结论要打折看 —— 它本行是听音频。 */
  async riskText(text) {
    if (!this.enabled || !text) return null;
    try {
      const out = await this._post('/risk', { text: String(text).slice(0, 6000) });
      if (!out || !out.ok || typeof out.p !== 'number') return null;
      return { p: out.p, verdict: out.verdict, ms: out.ms, mode: 'text' };
    } catch (e) {
      console.warn('[Sft] 文本判定失败（按缺席处理）：', e.message);
      return null;
    }
  }

  /** 给 /api/health 和自检用。没配就明说没配，不含糊。 */
  async ping() {
    if (!this.enabled) return { enabled: false, model: this.model, msg: '未配置 SFT_URL' };
    try {
      const ac = new AbortController();
      const timer = setTimeout(() => ac.abort(), 5000);
      let data;
      try {
        const r = await fetch(this.url + '/health', { signal: ac.signal });
        data = await r.json();
      } finally {
        clearTimeout(timer);
      }
      return {
        enabled: true,
        model: this.model,
        url: this.url,
        ok: !!data.ok,
        loading: !!data.loading,
        error: data.error || null,
        loadMs: data.loadMs || 0,
        runs: data.runs || 0,
      };
    } catch (e) {
      return { enabled: true, model: this.model, url: this.url, ok: false, msg: e.message };
    }
  }
}

module.exports = { Sft };
