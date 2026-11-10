'use strict';
/**
 * ============================================================================
 *  Pangu.js —— 华为云 MaaS 盘古大模型调用
 * ----------------------------------------------------------------------------
 *  定位：第二层，只在本地规则"拿不准"的时候介入。
 *
 *  【架构铁律，别改】
 *    L1 本地规则  → 毫秒级、离线、决定盖不盖屏
 *    L2 盘古模型  → 秒级、需联网、只做灰区复审 + 人话摘要，失败静默跳过
 *
 *    如果把"盖不盖屏"交给模型，网差就多等 1~3 秒，没网就整个失效，
 *    而那一刻钱正在动。这条不能妥协。
 *
 *  接口：华为云 MaaS 提供 OpenAI 兼容格式，鉴权只要一个 Bearer API Key。
 *    预置服务：https://api.modelarts-maas.com/v2/chat/completions
 *    专属部署：https://maas-cn-southwest-2.modelarts-maas.com/v1/infers/{id}/v1/chat/completions
 * ============================================================================
 */

const DEFAULT_URL = 'https://api.modelarts-maas.com/v2/chat/completions';
// 【选型定论 2026-10-29】DeepSeek-V4.1-Flash，不再改。
// 理由：非思考型（2s 出结果，不烧思考链）、输入¥3.20/输出¥4.80 每百万（比 glm-5.2 便宜 4~4.5 倍）、
// 中文强、同样挂在华为云 MaaS 上（赛题要求不丢）、不是盘古（需求明确不用盘古）。
// 备选（仅应急）：openpangu-2.0-flash（最便宜 ¥0.80/¥1.60）/ glm-5.2（贵且慢，已弃用）
const DEFAULT_MODEL = 'deepseek-v4.1-flash';

/**
 * 官方单价（元/百万 tokens），2026-09-22 起分时段计费（中国站贵阳一）。
 * 时段1 = 08:00-20:59，时段2 = 21:00-次日07:59（五折）。
 * 来源：huaweicloud.com/notice/20260920171230199.html
 */
const PRICE = {
  'deepseek-v4.1-flash': { in: 2.0, out: 8.0, cachedIn: 0.2 },
  'glm-5.2': { in: 8.0, out: 28.0, cachedIn: 2.0 },
  'openpangu-2.0-flash': { in: 0.2, out: 0.8, cachedIn: 0 },
};
// 闲时（21:00-07:59）打五折
function isOffPeak(d = new Date()) {
  const h = d.getHours();
  return h >= 21 || h < 8;
}

class Pangu {
  constructor(cfg = {}) {
    this.url = cfg.url || process.env.MAAS_URL || DEFAULT_URL;
    this.key = cfg.key || process.env.MAAS_KEY || '';
    this.model = cfg.model || process.env.MAAS_MODEL || DEFAULT_MODEL;
    this.timeout = Number(cfg.timeout || process.env.MAAS_TIMEOUT || 20000);
    // 成本记账：每调用一次就更新，供 /api/health 与答辩页展示
    this.lastUsage = null;   // { prompt_tokens, completion_tokens, total_tokens }
    this.lastCost = 0;       // 本次调用估算费用（元）
    this.totalCost = 0;      // 进程内累计费用（元）
    this.calls = 0;          // 进程内调用次数
  }

  /** 按 token 用量与当前时段估算费用（元） */
  costOf(usage, at = new Date()) {
    if (!usage) return 0;
    const p = PRICE[(this.model || '').toLowerCase()] || PRICE['deepseek-v4.1-flash'];
    const half = isOffPeak(at) ? 0.5 : 1;
    const pin = Number(usage.prompt_tokens || 0);
    const pout = Number(usage.completion_tokens || 0);
    const cached = Number(
      (usage.prompt_tokens_details && usage.prompt_tokens_details.cached_tokens) || 0
    );
    const miss = Math.max(0, pin - cached);
    return ((miss * p.in + cached * p.cachedIn + pout * p.out) / 1e6) * half;
  }

  /** 记录一次成功调用的用量与费用 */
  record(usage) {
    this.lastUsage = usage || null;
    this.calls += 1;
    this.lastCost = this.costOf(this.lastUsage);
    this.totalCost += this.lastCost;
  }

  get enabled() {
    return !!this.key;
  }

  /**
   * 连通性自检。把各种 HTTP 状态翻译成人话，方便现场排障。
   */
  async ping() {
    if (!this.enabled) {
      return { ok: false, msg: '未配置 API Key。请在环境变量里设 MAAS_KEY，或调用 /api/config 保存。' };
    }
    if (typeof fetch !== 'function') {
      return { ok: false, msg: '当前 Node 版本没有内置 fetch，请升级到 18 以上（或 npm i node-fetch）。' };
    }
    const t0 = Date.now();
    try {
      const resp = await this.chat([{ role: 'user', content: '回复两个字：正常' }], { maxTokens: 16 });
      return { ok: true, msg: `通��，耗时 ${Date.now() - t0}ms，返回：${String(resp).slice(0, 40)}` };
    } catch (e) {
      const map = {
        401: 'API Key 不正确或服务未开通。去 ModelArts Studio 检查「在线推理」里该模型是否已开通；新 Key 需等几分钟生效。',
        403: 'Key 有效但无权限，或账号欠费/未实名。',
        404: '地址或模型名写错了。走免费额度时，地址要抄「免费服务 → 调用说明 → OpenAI SDK」里那个，别用统一域名。',
        429: '调用太频繁，被限流了。等一分钟再试。',
      };
      const code = e.status || 0;
      return { ok: false, msg: map[code] || `调用失败：${e.message}` };
    }
  }

  /**
   * 底层调用。OpenAI 兼容格式。
   * @param {Array} messages [{role, content}]
   */
  async chat(messages, opt = {}) {
    // 思考控制，按模型家族处理：
    //  - 盘古系：system prompt 末尾加 /no_think 标签
    //  - glm 系：请求体加 thinking:{type:'disabled'}（实测生效，跳过 reasoning，5 秒内出结果；
    //    不加的话每次先"想"一轮，12 秒都打不住，复审全超时）
    //  - deepseek 系：如果将来换 deepseek，同样加 body 里 thinking:{type:'disabled'}
    const body = {
      model: this.model,
      messages,
      temperature: opt.temperature != null ? opt.temperature : 0.2,
      max_tokens: opt.maxTokens || 512,
      stream: false,
    };
    const m = (this.model || '').toLowerCase();
    if (m.includes('pangu')) {
      messages = messages.map(x =>
        x.role === 'system' ? { ...x, content: x.content + '\n/no_think' } : x
      );
    } else if (m.includes('glm') || m.includes('deepseek')) {
      body.thinking = { type: 'disabled' };
    }

    const ctrl = new AbortController();
    const tid = setTimeout(() => ctrl.abort(), this.timeout);
    let resp;
    try {
      resp = await fetch(this.url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${this.key}`,
        },
        body: JSON.stringify(body),
        signal: ctrl.signal,
      });
    } catch (e) {
      clearTimeout(tid);
      const err = new Error(e.name === 'AbortError' ? `请求超时（${this.timeout}ms）` : e.message);
      err.status = 0;
      throw err;
    }
    clearTimeout(tid);

    if (!resp.ok) {
      const txt = await resp.text().catch(() => '');

      // 自动纠错：模型 ID 大小写写错（控制台显示 DeepSeek-V4.1-Flash，实际 ID 常是小写）
      // 只在这类错误上重试一次，且不改 this.model，避免污染配置。
      const modelBad = resp.status === 400 || resp.status === 404;
      if (modelBad && /model/i.test(txt) && body.model !== body.model.toLowerCase()) {
        body.model = body.model.toLowerCase();
        const r2 = await fetch(this.url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${this.key}` },
          body: JSON.stringify(body),
        });
        if (r2.ok) {
          const d2 = await r2.json();
          this.record(d2.usage);
          const c2 = (d2.choices || [])[0] || {};
          return String((c2.message && c2.message.content) || '')
            .replace(/<thought>[\s\S]*?<\/thought>/gi, '').trim();
        }
      }

      const err = new Error(`HTTP ${resp.status} ${txt.slice(0, 200)}`);
      err.status = resp.status;
      throw err;
    }

    const data = await resp.json();
    // 成本记账（答辩时可现场展示"这一句话花了多少钱"）
    this.record(data.usage);
    const choice = (data.choices || [])[0] || {};
    let text = (choice.message && choice.message.content) || '';
    // 盘古快慢思考模型会先在 <thought> 里输出推理过程，只要最终答案
    text = text.replace(/<thought>[\s\S]*?<\/thought>/gi, '').trim();
    return text;
  }

  /**
   * 灰区复审：本地分落在 30~45 之间、拿不准的短信，交给模型判一次。
   * 返回 { isFraud:boolean, reason:string }，失败返回 null（调用方按本地结果走）。
   */
  async reviewSms(body, localScore) {
    if (!this.enabled) return null;
    const sys = [
      '你是反电信诈骗短信判别器，服务于中国老年用户。',
      '判断这条短信是不是诈骗短信。',
      '只输出一行 JSON，不要任何解释、不要 markdown 代码块。',
      '格式：{"is_fraud":true,"reason":"不超过20字的原因"}',
      '规则：公检法要转账、刷单返利、冒充客服退款索要验证码、虚假中奖、贷款验资 → 是诈骗。',
      '快递取件、银行交易提醒、账单还款、正常营销 → 不是诈骗。',
    ].join('\n');

    const usr = `短信正文：${body}\n本地规则初判分数：${localScore}（40~90 之间，属于拿不准）`;

    try {
      const txt = await this.chat(
        [{ role: 'system', content: sys }, { role: 'user', content: usr }],
        { maxTokens: 128, temperature: 0.1 }
      );
      const m = String(txt).match(/\{[\s\S]*\}/);
      if (!m) return null;
      const obj = JSON.parse(m[0]);
      return { isFraud: !!obj.is_fraud, reason: String(obj.reason || '').slice(0, 40) };
    } catch (e) {
      console.warn('[Pangu] reviewSms 跳过：', e.message);
      return null;
    }
  }

  /**
   * 一键上传：可疑短信（文字或截图）→ 结构化研判结论。
   * 这是"上传 / 识别 / 查询结论"三个核心 API 里的"识别"。
   *
   * 支持图片：deepseek-v4.1-flash 实测可吃 image_url（data URL 形式），
   * 一张 430×760 的手机截图约 830 prompt tokens，约 0.2 分钱。
   *
   * @param {{text?:string, imageBase64?:string, mime?:string}} input
   * @returns {Promise<null|{isFraud,stage,stageName,type,reason,advice,signals}>}
   */
  async analyzeSms(input = {}) {
    if (!this.enabled) return null;
    const { text = '', imageBase64 = null, mime = 'image/png' } = input;
    if (!text && !imageBase64) return null;

    const sys = [
      '你是反电信诈骗研判器，服务中国老年用户及其子女。',
      '输入可能是一条短信的文字，也可能是一张短信截图（需要你自己看图）。',
      '按四阶段判断这条信息走到哪一步：',
      '1=套近乎（拉关系、套信息）；2=吓唬（恐吓、威胁、制造紧张）；',
      '3=隔离（要求别告诉家人、独自操作、涉密）；4=要钱（转账、验证码、安全账户、投资充值）。',
      '0=不是诈骗（快递、银行交易提醒、验证码通知、正常营销）。',
      '只输出一行 JSON，不要 markdown 代码块，不要任何解释。格式：',
      '{"is_fraud":true,"stage":4,"type":"冒充公检法","reason":"不超过25字","advice":"给老人的一句行动建议，不超过20字","signals":["截图或短信里的关键话术片段","最多4条"]}',
    ].join('\n');

    const ask = text
      ? `请研判下面这条短信：\n${String(text).slice(0, 1200)}`
      : '请研判这张短信截图。';

    // 有图就走多模态 content 数组
    const content = imageBase64
      ? [
          { type: 'text', text: ask },
          { type: 'image_url', image_url: { url: `data:${mime};base64,${imageBase64}` } },
        ]
      : ask;

    try {
      const raw = await this.chat(
        [{ role: 'system', content: sys }, { role: 'user', content }],
        { maxTokens: 400, temperature: 0.1 }
      );
      const m = String(raw).match(/\{[\s\S]*\}/);
      if (!m) return null;
      const o = JSON.parse(m[0]);
      const stage = Math.max(0, Math.min(4, Number(o.stage) || 0));
      const NAMES = { 0: '无风险', 1: '套近乎', 2: '吓唬', 3: '隔离', 4: '要钱' };
      return {
        isFraud: !!o.is_fraud,
        stage,
        stageName: NAMES[stage],
        type: String(o.type || '').slice(0, 20),
        reason: String(o.reason || '').slice(0, 50),
        advice: String(o.advice || '').slice(0, 40),
        signals: Array.isArray(o.signals) ? o.signals.slice(0, 4).map(s => String(s).slice(0, 30)) : [],
      };
    } catch (e) {
      console.warn('[Pangu] analyzeSms 失败：', e.message);
      return null;
    }
  }

  /**
   * 通话复盘：一通陌生电话的转写文字 → 是不是诈骗、走到第几步、证据原话。
   *
   * 转写来自单声道录音，分不清哪句是老人说的、哪句是对方说的，
   * 所以提示词里专门交代：要靠语义判断说话人，证据只许摘原文。
   *
   * @param {string} transcript 转写全文
   * @returns {Promise<null|{isFraud,stage,stageName,type,reason,advice,evidence}>}
   */
  async judgeCall(transcript) {
    if (!this.enabled) return null;
    const text = String(transcript || '').trim();
    if (!text) return null;

    const sys = [
      '你是反电信诈骗研判器，服务中国老年用户及其子女。',
      '输入是一通陌生号码来电的录音转写稿，单声道，没有标注说话人，需要你根据语义自己区分老人和对方。',
      '按四阶段判断对方把骗局推进到了哪一步：',
      '1=套近乎（冒充熟人/机构拉关系、套个人信息）；2=吓唬（涉嫌犯罪、账户冻结、医保社保异常等制造紧张）；',
      '3=隔离（要求别告诉家人、别挂电话、换个安静地方、保密）；4=要钱（转账、安全账户、验证码、下载软件/共享屏幕、投资充值、买卡）。',
      '0=不是诈骗（家常、正常客服回访、快递、推销但不涉及钱款操作）。',
      '注意：这通电话已经挂断，advice 要说接下来该怎么做（比如别转账、别给验证码、打给子女），不要说“挂断”。',
      '只输出一行 JSON，不要 markdown 代码块，不要任何解释。格式：',
      '{"is_fraud":true,"stage":4,"type":"冒充公检法","reason":"不超过30字","advice":"给老人的一句行动建议，不超过20字","evidence":["必须是转写稿里的原话片段","最多4条，每条不超过30字"]}',
    ].join('\n');

    try {
      const raw = await this.chat(
        [{ role: 'system', content: sys }, { role: 'user', content: `通话转写稿：\n${text.slice(0, 6000)}` }],
        { maxTokens: 500, temperature: 0.1 }
      );
      const m = String(raw).match(/\{[\s\S]*\}/);
      if (!m) return null;
      const o = JSON.parse(m[0]);
      const stage = Math.max(0, Math.min(4, Number(o.stage) || 0));
      const NAMES = { 0: '无风险', 1: '套近乎', 2: '吓唬', 3: '隔离', 4: '要钱' };
      // 证据只保留真在原文里出现过的片段，防止模型编造"原话"
      const evidence = (Array.isArray(o.evidence) ? o.evidence : [])
        .map(s => String(s).trim().slice(0, 30))
        .filter(s => s && text.includes(s))
        .slice(0, 4);
      return {
        isFraud: !!o.is_fraud,
        stage,
        stageName: NAMES[stage],
        type: String(o.type || '').slice(0, 20),
        reason: String(o.reason || '').slice(0, 60),
        advice: String(o.advice || '').slice(0, 40),
        evidence,
      };
    } catch (e) {
      console.warn('[Pangu] judgeCall 失败：', e.message);
      return null;
    }
  }

  /**
   * 事件摘要：把一串手机状态翻译成一句人话，贴在子女端卡片上。
   * 这是评委最容易记住的一处 —— 子女不用自己拼信息。
   */
  async explain(events, who) {
    if (!this.enabled) return null;
    const sys = [
      '你把一串手机事件翻译成一句给家属看的中文摘要。',
      '要求：不超过 30 字，说清"发生了什么危险"，不要术语，不要编号，不要引号。',
      '示例：母亲正被冒充公检法的人引导转账。',
      '只输出这一句话，别的都不要。',
    ].join('\n');

    const usr = `对象：${who || '家里的老人'}\n事件：\n${events.map((e, i) => `${i + 1}. ${e}`).join('\n')}`;

    try {
      const txt = await this.chat(
        [{ role: 'system', content: sys }, { role: 'user', content: usr }],
        { maxTokens: 96, temperature: 0.3 }
      );
      return String(txt).replace(/[\r\n]+/g, ' ').trim().slice(0, 60) || null;
    } catch (e) {
      console.warn('[Pangu] explain 跳过：', e.message);
      return null;
    }
  }
}

module.exports = { Pangu };
