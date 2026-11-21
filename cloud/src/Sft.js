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
 *    POST /judge  → {ok, is_fraud, confidence, p, reason, scene, ms, mode}
 *                   **推荐**。按模型训练时的原生两轮问法：先判通话场景，再写分析、
 *                   输出 {"reason","confidence","is_fraud"}。GPU 上单次 3~70 秒。
 *    POST /risk   → {ok, p, verdict, ms, mode}
 *                   旧接口，只比较第一个 token「是」「否」的概率。0.15 秒出结果。
 *                   ⚠️ 2026-11-20 实测证明这个 p 与内容关系很弱（静音都能得 0.62），
 *                   模型从来没被训练成"只答一个字"。仅为兼容保留，不要用来判定。
 *
 *  为什么默认走 /judge（2026-11-21 定）：
 *    在冻结的 30 通真人录音上，/judge 的 p ≥ 0.85 时召回 15/15、误报 0/15；
 *    同一批录音走 /risk 得到的分数与内容几乎无关。把判定建立在 /risk 上
 *    等于用一个没校准的量尺去卡阈值。
 *    代价是慢（中位 8 秒、P90 45 秒），所以 CallAudio 那边用 Promise.race 限时，
 *    超时按"第三票缺席"处理，绝不拖垮整条复盘。
 * ============================================================================
 */

class Sft {
  constructor(env = process.env) {
    this.url = String(env.SFT_URL || '').replace(/\/+$/, '');
    this.model = 'AntiFraud-SFT';
    /** /judge 要生成几百字，超时给够；真正的限时在 CallAudio 的 SFT_WAIT_MS */
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
   * 把 /judge 的原始返回归一成融合层能用的形状。
   * 拿不到结论就返回 null —— 缺一票不算错，编一票才是。
   */
  _normJudge(out, mode) {
    if (!out || !out.ok) return null;
    // 有声段不足 1 秒：模型根本没判，这不是"判了没事"，必须当缺席
    if (out.skipped) return null;
    if (out.is_fraud === null || out.is_fraud === undefined) return null;
    const isFraud = !!out.is_fraud;
    let p = typeof out.p === 'number' ? out.p : null;
    if (p === null && typeof out.confidence === 'number') {
      // 服务端没给 p 时自己换算：p 是「判为诈骗的把握」，
      // 判诈骗时取 confidence，判正常时取 1-confidence
      p = isFraud ? out.confidence : 1 - out.confidence;
    }
    if (typeof p !== 'number' || !isFinite(p)) return null;
    return {
      p: Math.min(1, Math.max(0, p)),
      isFraud,
      confidence: typeof out.confidence === 'number' ? out.confidence : null,
      scene: out.scene || '',
      reason: out.reason || '',
      verdict: out.verdict || (isFraud ? 'fraud' : 'normal'),
      ms: out.ms || 0,
      mode,
    };
  }

  /**
   * 听一段录音，用原生两轮问法（推荐）。
   * @param {{audio:Buffer, name?:string, mime?:string}} input
   * @returns {Promise<{p:number,isFraud:boolean,confidence:number|null,scene:string,
   *                    reason:string,verdict:string,ms:number,mode:string}|null>}
   */
  async judgeAudio({ audio, name, mime }) {
    if (!this.enabled || !audio || !audio.length) return null;
    try {
      const out = await this._post('/judge', {
        audio_b64: audio.toString('base64'),
        name: name || 'call.wav',
        mime: mime || '',
      });
      const r = this._normJudge(out, 'audio');
      if (!r) console.warn('[Sft] /judge 没给结论（跳过或格式异常），按缺席处理');
      return r;
    } catch (e) {
      console.warn('[Sft] 音频判定失败（按缺席处理）：', e.message);
      return null;
    }
  }

  /** 只有转写稿时也能问一嘴；它本行是听音频，文本票只作参考。 */
  async judgeText(text) {
    if (!this.enabled || !text) return null;
    try {
      const out = await this._post('/judge', { text: String(text).slice(0, 6000) });
      return this._normJudge(out, 'text');
    } catch (e) {
      console.warn('[Sft] 文本判定失败（按缺席处理）：', e.message);
      return null;
    }
  }

  /**
   * 旧接口 /risk。只比首个 token 的概率，已证实与内容关系很弱。
   * @deprecated 别用它判定，留着只为兼容老部署和对照实验。
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

  /**
   * 旧接口 /risk 的文本版。
   * @deprecated 同 riskAudio。
   */
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
