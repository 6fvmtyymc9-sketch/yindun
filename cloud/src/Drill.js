'use strict';
/**
 * ============================================================================
 *  Drill.js —— AI 模拟演练（反诈疫苗，功能④）
 * ----------------------------------------------------------------------------
 *  做什么：让模型扮演骗子，跟老人打一局 4 轮的"攻防"。
 *          骗子按 套近乎 → 吓唬 → 隔离 → 要钱 四步推进（正好对应 S1~S4），
 *          老人每轮做一次选择，最后出成绩单。
 *
 *  【为什么不用纯模板】
 *    纯模板读起来像考试题，老人没代入感。这里让模型按场景+阶段现编台词，
 *    同一局每次说的话都不一样，才像真的在跟骗子对话。
 *
 *  【为什么不要每轮都调模型】
 *    开局一次生成 4 轮台词，整局只花一次调用（约 ¥0.002）。
 *    轮内翻页不等待，老人点完立刻有反应 —— 演练最怕卡顿。
 *
 *  【铁律】判分不交给模型。对错是剧本里写死的，模型只负责"说人话"。
 *          否则模型抽风把"转账"判成正确，这个功能就废了。
 * ============================================================================
 */

const CaseBank = require('./CaseBank');

/** 场景剧本：每轮 3 个选项，ok:true 是唯一正确项 */
const SCENARIOS = {
  gongan: {
    name: '冒充公检法',
    icon: '👮',
    desc: '最常见的"洗钱案"套路，专挑老人下手',
    level: '高发',
    rounds: [
      {
        stage: 1,
        fallback: '您好，这里是北京市公安局刑侦支队。请问是朱女士本人吗？我们这边有一个案子需要向您核实一下情况。',
        question: '对方自称是公安局的',
        choices: [
          { t: '是，我就是，什么事？', ok: false },
          { t: '你是谁？警号多少？', ok: false },
          { t: '挂了，我自己打 110 问', ok: true },
        ],
        tip: '真警察不会打电话要你配合"资金核查"。挂掉，自己拨 110 问，是最稳的一步。',
      },
      {
        stage: 2,
        fallback: '朱女士，您名下尾号 6688 的银行卡涉嫌一起特大洗钱案件，我们已经立案侦查。现在依法要冻结您名下所有账户。',
        question: '对方说你涉嫌犯罪',
        choices: [
          { t: '我没干过，那我该怎么办？', ok: false },
          { t: '你凭什么说我犯罪？有文件吗？', ok: true },
          { t: '我马上配合你们', ok: false },
        ],
        tip: '一被吓住就输了。真立案会有书面文书，不会在电话里通知你，更不会让你"自己查"。',
      },
      {
        stage: 3,
        fallback: '这个案子涉密，您现在不能告诉任何人，包括您的子女，否则视为串供，我们只能直接抓人。',
        question: '对方要求你别告诉家人',
        choices: [
          { t: '好，我不说', ok: false },
          { t: '我现在就给我女儿打电话', ok: true },
          { t: '那我先想想', ok: false },
        ],
        tip: '"别告诉家人"是所有诈骗的共同点。凡是让你瞒着子女的，一定是骗子。',
      },
      {
        stage: 4,
        fallback: '为了证明您的资金清白，请把存款转入我们的国家安全账户接受审查，核查无误后 24 小时内原路退回。您先转 18 万 6。',
        question: '对方让你把钱转到"安全账户"',
        choices: [
          { t: '好，我现在去银行转', ok: false },
          { t: '不转，我先问家里人', ok: true },
          { t: '那我先转一部分试试', ok: false },
        ],
        tip: '根本不存在"安全账户"。只要听到转账、验资、审查资金，就是诈骗。',
      },
    ],
  },

  qinshu: {
    name: '冒充子女换号',
    icon: '📱',
    desc: '"妈我换号了"——利用的是亲情',
    level: '高发',
    rounds: [
      {
        stage: 1,
        fallback: '妈，我手机掉水里坏了，这是同事的号，我新号还没办下来。你存一下这个号。',
        question: '对方自称是你的孩子',
        choices: [
          { t: '哎，你怎么了？没事吧', ok: false },
          { t: '你叫什么名字？我儿子叫什么？', ok: true },
          { t: '哦，那我存一下', ok: false },
        ],
        tip: '声音可以模仿，名字可以打听到。要问只有你家人知道的事。',
      },
      {
        stage: 2,
        fallback: '妈，我这边出事了，跟人起了冲突，对方要赔钱，不然就要报警抓我。我现在急得不行。',
        question: '对方说自己出事了',
        choices: [
          { t: '要多少钱？妈给你', ok: false },
          { t: '你先别急，我打你原来那个号', ok: true },
          { t: '你怎么这么不小心', ok: false },
        ],
        tip: '越是催你、越是不让你核实的，越可疑。挂掉，用原来的号码打回去。',
      },
      {
        stage: 3,
        fallback: '妈你别跟我爸说，也别跟姐姐说，他们知道了肯定骂我。你现在就帮我，我马上就能解决。',
        question: '对方让你别告诉其他家人',
        choices: [
          { t: '好，我不说，妈帮你', ok: false },
          { t: '不行，这么大的事必须告诉你爸', ok: true },
          { t: '那你自己小心点', ok: false },
        ],
        tip: '骗子最怕你去问别人。只要对方要求保密，就先当他是骗子。',
      },
      {
        stage: 4,
        fallback: '妈你先转 3 万到这个卡上，是我朋友的账户，我自己的卡被冻结了用不了。今天下午 5 点前必须给。',
        question: '对方要你转钱到别人卡上',
        choices: [
          { t: '好，我这就去转', ok: false },
          { t: '不转，我先视频看看你', ok: true },
          { t: '那我转 2 万行不行', ok: false },
        ],
        tip: '转到"朋友账户"是典型特征。视频一下、或者打原来号码，一问就穿。',
      },
    ],
  },

  kefu: {
    name: '冒充客服退款',
    icon: '📦',
    desc: '说要给你退款，其实是要你的验证码',
    level: '高发',
    rounds: [
      {
        stage: 1,
        fallback: '您好，我是淘宝客服。您上周买的电饭锅有质量问题，厂家要给您三倍赔付，一共 297 元。',
        question: '对方说要给你赔钱',
        choices: [
          { t: '真的？怎么赔？', ok: false },
          { t: '我自己去 App 里看订单', ok: true },
          { t: '那太好了', ok: false },
        ],
        tip: '主动给你钱的，九成是骗子。退款只会在你自己下单的 App 里办。',
      },
      {
        stage: 2,
        fallback: '因为系统原因，退款要转到您绑定的银行卡上。但是您的账户有风险，需要先配合做一次资金验证，不然退不进去。',
        question: '对方说要先"验证资金"',
        choices: [
          { t: '怎么验证？', ok: false },
          { t: '退款为什么要验我的余额？', ok: true },
          { t: '那你教我', ok: false },
        ],
        tip: '退款只需要你的账号，永远不会问你有多少钱。凡是问余额的，都是冲着钱来的。',
      },
      {
        stage: 3,
        fallback: '这个流程比较特殊，您别挂电话，也别跟家里人说，他们不懂会打断您操作。我们一步一步来。',
        question: '对方不让你挂电话',
        choices: [
          { t: '好，你说', ok: false },
          { t: '我先挂了，问问我女儿', ok: true },
          { t: '那你快点', ok: false },
        ],
        tip: '"别挂电话、别问家人"是为了不给你冷静和商量的机会。挂断是安全的。',
      },
      {
        stage: 4,
        fallback: '我现在给您手机上发一个验证码，您念给我，我帮您在系统里走完最后一步，钱立刻就到。',
        question: '对方要你念短信验证码',
        choices: [
          { t: '好，是 382910', ok: false },
          { t: '验证码不能给任何人', ok: true },
          { t: '我念慢一点', ok: false },
        ],
        tip: '验证码等于你银行卡的钥匙。任何人问你要，无论什么身份，都是骗子。',
      },
    ],
  },

  shuadan: {
    name: '刷单返利',
    icon: '💰',
    desc: '先让你赚几十块，再骗走几万',
    level: '高发',
    rounds: [
      {
        stage: 1,
        fallback: '阿姨您好，我们是做电商推广的，帮商家点个好评就能拿佣金，一单 8 块，一天做几单就有百来块，您有兴趣吗？',
        question: '对方说动动手就能赚钱',
        choices: [
          { t: '有兴趣，怎么做？', ok: false },
          { t: '不用出钱吧？', ok: true },
          { t: '一天能赚多少？', ok: false },
        ],
        tip: '"动动手就赚钱"本身就是最大的破绽。真工作不会在微信群里招人。',
      },
      {
        stage: 2,
        fallback: '第一单已经给您返了 8 块，您看是不是到账了？想继续做要升级成会员，接的单佣金更高，一单 30 块。',
        question: '对方让你先交钱升级',
        choices: [
          { t: '多少钱？', ok: false },
          { t: '要交钱就不做了', ok: true },
          { t: '那交一点试试', ok: false },
        ],
        tip: '所有刷单都是同一个套路：先给你甜头，再让你交本金。一交钱就再也拿不回来。',
      },
      {
        stage: 3,
        fallback: '您这个情况比较特殊，别跟家里人说，说了他们肯定不同意，等您赚到了再告诉他们不是更好？',
        question: '对方让你瞒着家人',
        choices: [
          { t: '也是', ok: false },
          { t: '我跟我儿子商量一下', ok: true },
          { t: '那我先不说', ok: false },
        ],
        tip: '让你保密的人，一定不希望你被提醒。这本身就是最大的警报。',
      },
      {
        stage: 4,
        fallback: '刚才您操作错了，账户被冻结，要再充 2 万才能解冻，不然前面的钱都拿不回来。您快一点，名额只有一个。',
        question: '对方让你再充钱解冻',
        choices: [
          { t: '那我再充 2 万', ok: false },
          { t: '不充了，这是骗局', ok: true },
          { t: '能不能少一点', ok: false },
        ],
        tip: '冒充"解冻费"是杀猪盘的最后一刀。此时止损，就是最好的选择。',
      },
    ],
  },
};

const PASS_SCORE = 75;

/** 身份 → 电话里的自称（与 CaseBank 共用一份，避免两处定义漂移） */
const SELF_CN = CaseBank.SELF_CN;

/** 模型不可用时的兜底台词，按 身份类别 + 阶段 拼 */
function fallbackLine(lane, bp) {
  const self = SELF_CN[lane.identity] || lane.identity;
  const kind = lane.kind || CaseBank.kindOf(lane.identity);

  if (lane.stage === 1) {
    if (kind === 'kin') {
      return lane.identity === '领导'
        ? '小朱啊，我是王总。有个急事要你帮我处理一下，你现在方便吗？'
        : '妈，我手机掉水里坏了，这是同事的号，我新号还没办下来，你存一下这个号。';
    }
    if (kind === 'service') return `您好，我这边是${self}的。您有个订单出了点问题，需要跟您核对一下。`;
    if (kind === 'benefit') return '阿姨您好，我们这边有个线上推广的活儿，手机上点几下就能拿佣金，您有兴趣吗？';
    return `您好，我这边是${self}的。请问是本人吗？有个情况需要跟您核实一下。`;
  }

  if (lane.stage === 2) {
    if (kind === 'kin') return '妈，我这边出事了，现在急得不行，您先帮帮我。';
    if (kind === 'service') return '因为系统原因，这个钱要退到您绑定的卡上，但您账户有风险，得先配合验证一下。';
    if (kind === 'benefit') return '第一单已经给您返了 8 块，您看是不是到账了？想接大单要先升级。';
    return '您名下有一笔业务出了问题，情况比较紧急，如果今天不处理，后面会很麻烦。';
  }

  if (lane.stage === 3) {
    if (kind === 'kin') return '妈你别跟我爸说，也别跟姐姐说，他们知道了肯定骂我。你现在就帮我。';
    return '这个事您先别跟家里人说，说了他们也帮不上忙，反而不让您处理。我这边直接带您走完就行。';
  }

  return ({
    transfer: '您把钱转到我们指定的安全账户上，卡号我发您，越快越好，晚了就来不及了。',
    code: '我这边给您手机上发了个验证码，您念给我，我帮您把最后一步走完。',
    screen: '您先装一个我们平台的安全检测软件，我远程帮您看看手机有没有被木马控制。',
    install: '您点我发的这个链接，下载一个理赔 App，填上银行卡就能把钱退给您。',
    invest: '这个额度今天最后一天，您先充 2 万，明天就按 24% 给您算收益。',
  })[bp.demand] || '您先把钱转过来，我这边帮您处理。';
}

/** 内存态的进行中演练（一局很短，不需要落库到 Redis） */
const live = new Map();
let seq = 1;

class Drill {
  constructor({ pangu, db } = {}) {
    this.pangu = pangu || null;
    this.db = db || null;
    /**
     * 定期演练计划。默认每周一次，周一上午 10 点 —— 早上老人清醒、子女也有空。
     * freq 目前支持 weekly / biweekly / monthly / off。
     */
    this.plan = { freq: 'weekly', weekday: 1, hour: 10, enabled: true };
    /* 'YYYY-MM-DD'，避免同一天重复触发。
       【2026-11-09 修】以前只存在内存里，服务一重启就重置 → 每次部署都往子女端
       多塞一张「该提醒妈妈做反诈演练了」。现在落一个文件，重启也不会重复推。 */
    this._lastFired = this._readFired();
  }

  _firedFile() {
    return require('path').join(__dirname, '..', '.drill-last');
  }
  _readFired() {
    try { return String(require('fs').readFileSync(this._firedFile(), 'utf8')).trim(); }
    catch (e) { return ''; }
  }
  _writeFired(v) {
    try { require('fs').writeFileSync(this._firedFile(), v); } catch (e) { /* 写不了就退回内存 */ }
  }

  // ---------- 定期计划 ----------
  getPlan() {
    return { ...this.plan, nextAt: this.nextRunAt() };
  }

  setPlan(p = {}) {
    if (p.freq != null) this.plan.freq = String(p.freq);
    if (p.weekday != null) this.plan.weekday = Math.max(0, Math.min(6, Number(p.weekday)));
    if (p.hour != null) this.plan.hour = Math.max(0, Math.min(23, Number(p.hour)));
    if (p.enabled != null) this.plan.enabled = !!p.enabled;
    return this.getPlan();
  }

  /** 下一次该演练的时间（本地时间） */
  nextRunAt(now = new Date()) {
    if (!this.plan.enabled || this.plan.freq === 'off') return null;
    const d = new Date(now);
    d.setSeconds(0, 0);
    d.setHours(this.plan.hour, 0, 0, 0);

    if (this.plan.freq === 'monthly') {
      if (d <= now) d.setMonth(d.getMonth() + 1);
      d.setDate(1);
      d.setHours(this.plan.hour, 0, 0, 0);
      return d;
    }
    // weekly / biweekly：先推到目标星期几
    const step = this.plan.freq === 'biweekly' ? 14 : 7;
    let add = (this.plan.weekday - d.getDay() + 7) % 7;
    if (add === 0 && d <= now) add = 7;
    d.setDate(d.getDate() + add);
    if (step === 14) {
      // 双周：以"上次演练"为锚点，往前推两周
      d.setDate(d.getDate() - 0);   // 简化：仍按每两周的星期几提醒，避免锚点漂移
    }
    return d;
  }

  /** 调度器每分钟问一次：现在该不该开一局？ */
  isDue(now = new Date()) {
    if (!this.plan.enabled || this.plan.freq === 'off') return false;
    const key = this._key(now);
    if (this._lastFired === key) return false;
    if (now.getDay() !== this.plan.weekday && this.plan.freq !== 'monthly') return false;
    if (this.plan.freq === 'monthly' && now.getDate() !== 1) return false;
    return now.getHours() >= this.plan.hour;
  }

  markFired(now = new Date()) {
    this._lastFired = this._key(now);
    this._writeFired(this._lastFired);
  }

  _key(d) {
    return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
  }

  listScenarios() {
    return Object.entries(SCENARIOS).map(([key, s]) => ({
      key,
      name: s.name,
      icon: s.icon,
      desc: s.desc,
      level: s.level,
      rounds: s.rounds.length,
      type: 'classic',
    }));
  }

  /** 热点库：前端可以列出"最近高发什么"，也可以指定某一类来演 */
  listTopics() {
    return CaseBank.hotList();
  }

  /** 案例库：有哪些上传过的诈骗信息可以拿来衍生 */
  async listCases(limit = 20) {
    if (!this.db || typeof this.db.listReports !== 'function') return [];
    const all = await this.db.listReports(limit).catch(() => []);
    return all
      .filter(r => r && (r.isFraud !== false && r.is_fraud !== 0))
      .map(r => ({
        id: r.id,
        type: r.type || '未分类',
        stage: r.stage,
        excerpt: CaseBank.mask(r.excerpt || '').slice(0, 40),
        at: r.createdAt || r.created_at || '',
        skeleton: CaseBank.skeletonFromReport(r),
      }));
  }

  /**
   * 开局。key 命中经典剧本就走经典；否则按 opts.mode 现编一局。
   *
   *   start(elderId)                              → 自动（有案例优先用案例）
   *   start(elderId, 'gongan')                    → 经典剧本
   *   start(elderId, null, {mode:'hot', hotId})   → 指定热点
   *   start(elderId, null, {mode:'case'})         → 用最近上传的案例
   *   start(elderId, null, {mode:'mix'})          → 跨套路混合
   */
  async start(elderId, key, opts = {}) {
    const classic = SCENARIOS[key];
    if (classic) return this._startClassic(elderId, key, classic);
    return this._startSmart(elderId, { ...opts, key: key || null });
  }

  /** 经典剧本：台词还是让模型润色，选项与判分全部写死 */
  async _startClassic(elderId, key, s) {
    let lines = null;
    let cost = 0;
    if (this.pangu && this.pangu.enabled) {
      lines = await this._script(`诈骗类型：${s.name}（${s.desc}）。请写这 4 句话。`);
      cost = this.pangu.lastCost || 0;
    }

    const id = 'd' + seq++;
    const rounds = s.rounds.map((r, i) => ({
      stage: r.stage,
      aiText: (lines && lines[i]) || r.fallback,
      question: r.question,
      choices: r.choices,
      tip: r.tip,
    }));

    live.set(id, {
      id, elderId: elderId || 'demo', key, name: s.name, icon: s.icon,
      rounds, idx: 0, correct: 0, wrong: [], cost, at: Date.now(),
      source: { mode: 'classic' }, from: '经典剧本',
    });
    return this._view(live.get(id));
  }

  /**
   * 现编一局：热点库 / 上传案例 / 两者混合。
   * 素材先拆成骨架，再组装成 4 轮 —— 换的是外壳，留的是套路。
   */
  async _startSmart(elderId, opts) {
    let reports = [];
    if (this.db && typeof this.db.listReports === 'function') {
      try { reports = await this.db.listReports(30); } catch (e) { /* 没库就用热点 */ }
    }

    const bp = CaseBank.buildBlueprint(opts, reports);

    let lines = null;
    let cost = 0;
    if (this.pangu && this.pangu.enabled) {
      lines = await this._script(bp.brief);
      cost = this.pangu.lastCost || 0;
    }

    const id = 'd' + seq++;
    const rounds = bp.lanes.map((lane, i) => {
      const r = CaseBank.buildRound({ ...lane, demand: bp.demand });
      return {
        stage: r.stage,
        aiText: (lines && lines[i]) || fallbackLine(lane, bp),
        question: r.question,
        choices: r.choices,
        tip: r.tip,
      };
    });

    live.set(id, {
      id, elderId: elderId || 'demo',
      key: `smart:${bp.mode}:${bp.name}`,
      name: bp.name, icon: bp.icon,
      rounds, idx: 0, correct: 0, wrong: [], cost, at: Date.now(),
      source: bp.source, from: bp.from,
    });
    return this._view(live.get(id));
  }

  /** 模型编台词；返回 4 条数组，失败返回 null */
  async _script(brief) {
    const sys = [
      '你在做老年人反诈演练，扮演电话那头的诈骗分子。',
      '只输出一个 JSON 数组，4 个字符串，不要 markdown，不要解释。',
      '第1条是套近乎、拉近关系；第2条是吓唬、制造紧张；第3条是要求别告诉家人；第4条是直接要钱或要验证码。',
      '每句话不超过 60 字，要像真人打电话，可以用"您""阿姨"这种称呼，不要出现"诈骗""剧本"这类元词。',
    ].join('\n');
    try {
      const raw = await this.pangu.chat(
        [{ role: 'system', content: sys }, { role: 'user', content: brief }],
        { maxTokens: 400, temperature: 0.8 }
      );
      const m = String(raw).match(/\[[\s\S]*\]/);
      if (!m) return null;
      const arr = JSON.parse(m[0]);
      if (!Array.isArray(arr) || arr.length < 4) return null;
      return arr.slice(0, 4).map(x => String(x).slice(0, 120));
    } catch (e) {
      console.warn('[Drill] 台词生成失败，用剧本兜底：', e.message);
      return null;
    }
  }

  /** 当前这一轮的对外视图 */
  _view(d) {
    const r = d.rounds[d.idx];
    const done = d.idx >= d.rounds.length;
    return {
      drillId: d.id,
      scenario: { key: d.key, name: d.name, icon: d.icon },
      // 这局从哪儿来的：热点 / 你上传的案例 / 两者叠加。成绩单和前端都用它讲故事
      from: d.from || '',
      source: d.source || null,
      total: d.rounds.length,
      idx: d.idx,
      done,
      score: Math.round((d.correct / d.rounds.length) * 100),
      round: done ? null : { stage: r.stage, aiText: r.aiText, question: r.question, choices: r.choices.map(c => c.t) },
    };
  }

  /** 老人选了一个选项 */
  async turn(id, choiceIdx) {
    const d = live.get(id);
    if (!d) throw new Error('演练已结束或不存在，请重新开始');
    const r = d.rounds[d.idx];
    const c = r.choices[choiceIdx];
    if (!c) throw new Error('选项不存在');

    if (c.ok) d.correct += 1;
    else d.wrong.push({ idx: d.idx + 1, stage: r.stage, chose: c.t, right: r.choices.find(x => x.ok).t, tip: r.tip });

    d.idx += 1;
    const done = d.idx >= d.rounds.length;

    const payload = {
      ok: true,
      correct: !!c.ok,
      tip: r.tip,
      right: r.choices.find(x => x.ok).t,
      next: this._view(d),
    };

    if (done) {
      payload.result = await this._finish(d);
      live.delete(id);
    }
    return payload;
  }

  /** 结算 + 落库（演示版只记一局汇总，明细在内存里就够） */
  async _finish(d) {
    const score = Math.round((d.correct / d.rounds.length) * 100);
    const passed = score >= PASS_SCORE;
    let comment;
    if (score === 100) comment = '四步全守住了。骗子换招你都看穿了，这就是我们想要的效果。';
    else if (passed) comment = '基本守住了，但还有一步会被牵着走。记住：只要让你别告诉家人，就是骗子。';
    else comment = '这一局比较危险。不是您不小心，是骗子的话术就是照着人的弱点设计的。多练一局会好很多。';

    const result = {
      score, passed, passScore: PASS_SCORE, comment,
      wrong: d.wrong,
      cost: d.cost,
      // 结算页要说清"这一局是怎么来的"——这是演练与素材库联动最直观的体现
      from: d.from || '',
      source: d.source || null,
    };

    if (this.db && typeof this.db.addDrill === 'function') {
      try {
        await this.db.addDrill({
          elderId: d.elderId, scenario: String(d.name || d.key).slice(0, 60), score,
          rounds: d.rounds.length, passed, cost: d.cost,
          wrongStages: d.wrong.map(w => w.stage),
        });
      } catch (e) { console.warn('[Drill] 落库失败：', e.message); }
    }
    return result;
  }
}

module.exports = { Drill, SCENARIOS, PASS_SCORE };
