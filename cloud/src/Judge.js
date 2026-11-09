'use strict';
/**
 * ============================================================================
 *  Judge.js —— 风险判定引擎（云端版）
 * ----------------------------------------------------------------------------
 *  这是整个系统的"大脑"。安卓版有一个等价的 Java 实现（app/src/.../Judge.java），
 *  两边读同一份 rules.json，保证手机上算的和云上算的是同一个结果。
 *
 *  设计原则（答辩必答）：
 *    1. 纯本地计算，毫秒级，不依赖任何外部服务。
 *       模型（盘古）只在第二层做灰区复审，绝不影响"盖不盖屏"这个决定。
 *    2. 完全可解释。每次判定都返回命中了哪些词、各多少分，
 *       能当场向评委复现"为什么报警"。
 *    3. 规则即数据。读外部 JSON，改规则不需要改代码、不需要重新部署。
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');

/** 禁止字段：规则里一旦出现这些，引擎拒绝启动。
 *  它们对应的信号在安卓/浏览器里根本拿不到（通话内容、录音、微信正文）。
 *  这道闸门防的不是黑客，是防止规则作者写出永远不可能触发的死规则。 */
const FORBIDDEN_FIELDS = [
  'call.content', 'call.audio', 'call.record',
  'wx.chat', 'sms.code', 'transcript', 'voice.print',
];

/** 阶段高低序。用来在一堆命中词里挑出"骗子走到的最远那一步"。
 *  2026-11-04 引入：阶段判定从"总分落桶"改为"最高命中阶段词"，这是新模型的核心。 */
const STAGE_RANK = { S1: 1, S2: 2, S3: 3, S4: 4 };

/** 链接特征：正文里有没有"可点的网址"。
 *  两个分支：①带协议头的完整网址 ②不带协议头的短链（t.cn/xxx，骗子最常用）。
 *  只认形式不认域名真假 —— 核验域名要联网，短信到达那一秒来不及。 */
const URL_PATTERN = /(?:https?:\/\/|www\.)[a-z0-9\-._/?=&%#:@+~]+|\b(?:t\.cn|bit\.ly|dwz\.cn|url\.cn|s\.cn|w\.cn|rb\.gy|suo\.im|6url\.cn|tinyurl\.com|j\.mp)\/[a-z0-9\-._/?=&%#]+/i;



class Judge {
  /**
   * @param {object} rules 规则对象（从 rules.json 读出来的）
   */
  constructor(rules) {
    this.validate(rules);
    this.rules = rules;

    // 把词库预编译成数组，避免每次判定都遍历对象
    this.keywords = (rules.sms_keywords || []).map(k => ({
      word: String(k.word),
      weight: Number(k.weight) || 0,
      tag: k.tag || null,
      note: k.note || '',
    }));
    this.negatives = (rules.sms_negative || []).map(k => ({
      word: String(k.word),
      weight: Number(k.weight) || 0,
      note: k.note || '',
    }));
    this.stages = rules.stages || {};
    this.levels = rules.levels || { watch: 25, warn: 45, high: 70, block: 90 };
    this.combos = rules.combos || [];
    this.silence = rules.silence || {};
    // 官方宣传短信免打扰（rules.notice_alert）。缺省关闭，老规则文件不受影响。
    this.notice = rules.notice_alert || null;
  }

  /**
   * 官方宣传短信识别 —— 免打扰的唯一出口。
   *
   * 【2026-11-04 新增】A 线提出的真实盲区：反诈宣传短信本身就是一堆高危词。
   *   「【中国移动】警示：近期冒充客服要求共享屏幕的诈骗高发，请勿配合。」
   * 旧逻辑判 S4/block，运营商每周推一条，老人会被反复盖屏到直接卸载 App。
   *
   * 判别依据不是词，是**句式人称**：宣传短信是第三人称告诫（"警惕冒充…""诈骗高发"），
   * 诈骗短信是第二人称指令（"请将资金转入…""把验证码报给我"）。
   *
   * 三道闸门全过才免打扰：
   *   ① 来源必须是 service（陌生号发的一律不免）
   *   ② 命中 ≥ minMarkers 个宣传标记词（单个"警惕"不够，骗子也写"请提高警惕不要声张"）
   *   ③ 不含任何 veto 词（"请将""报给我""点击链接"… 出现即视为真指令，立刻取消豁免）
   *
   * @returns {string|null} 命中说明，未命中返回 null
   */
  noticeOf(text, who) {
    const cfg = this.notice;
    if (!cfg || !cfg.enabled) return null;
    if (cfg.onlyWho && who !== cfg.onlyWho) return null;

    const found = [];
    for (const m of (cfg.markers || [])) {
      if (text.includes(m)) found.push(m);
    }
    if (found.length < (cfg.minMarkers || 2)) return null;

    for (const v of (cfg.veto || [])) {
      if (text.includes(v)) return null;
    }
    return `官方宣传(${found.join('、')})`;
  }

  /** 规则自检：字段越界、阈值顺序错乱，都在启动时炸出来，不等到演示现场 */
  validate(rules) {
    if (!rules || typeof rules !== 'object') throw new Error('rules 不是对象');
    if (!Array.isArray(rules.sms_keywords)) throw new Error('rules.sms_keywords 必须是数组');

    // ① 禁止字段扫描：递归找所有字符串值，看有没有踩线
    const raw = JSON.stringify(rules);
    for (const f of FORBIDDEN_FIELDS) {
      if (raw.includes(f)) {
        throw new Error(
          `规则里出现禁止字段 "${f}"。这个信号在设备上拿不到，规则永远不会触发。请换成可观测的动作。`
        );
      }
    }

    // ② 阈值必须递增，否则分级会错乱
    const lv = rules.levels || {};
    const seq = [lv.watch, lv.warn, lv.high, lv.block];
    for (let i = 1; i < seq.length; i++) {
      if (!(seq[i] > seq[i - 1])) {
        throw new Error(`levels 阈值必须递增，第 ${i} 项 ${seq[i]} 不大于前一项 ${seq[i - 1]}`);
      }
    }
  }

  // ==========================================================================
  //  一、短信评分
  // ==========================================================================

  /**
   * 给一条短信打分
   * @param {string} body  短信正文
   * @param {string} who   号码身份：contact（通讯录）/ service（服务号）/ stranger（陌生人）
   * @param {boolean} isCode 是不是验证码短信
   * @returns {{score:number, hits:Array, stage:string, level:string, why:string}}
   */
  scoreSms(body, who, isCode) {
    // 与 App 端 Judge.java 对齐：统一转小写再比较（"APP"/"app"、"ETC"/"etc" 算同一个词）
    const text = String(body || '').toLowerCase();
    let score = 0;
    const hits = [];

    // ---------- 1. 正向词加权 ----------
    for (const k of this.keywords) {
      // isCode 已确认是真验证码短信时，"验证码"这个词必然出现，按发送方已经在第 4 步处理过，
      // 词库不再重复计分 —— 否则银行正常验证码短信会被 "验证码+S4" 直接推到盖屏。
      if (isCode && k.word === '验证码') continue;
      if (text.includes(k.word.toLowerCase())) {
        score += k.weight;
        hits.push({ word: k.word, weight: k.weight, tag: k.tag });
      }
    }

    // ---------- 2. 反例词减分（压低误报的关键） ----------
    for (const k of this.negatives) {
      if (text.includes(k.word.toLowerCase())) {
        score += k.weight; // weight 本身是负数
        hits.push({ word: k.word, weight: k.weight, tag: null });
      }
    }

    // ---------- 3. 号码身份加权 ----------
    // 同样的内容，陌生人发的比服务号发的危险。这是"身份"不是"词"，所以不写在 rules.json 里。
    if (who === 'stranger') {
      score += 15;
      hits.push({ word: '陌生号码', weight: 15, tag: null });
    } else if (who === 'contact') {
      // 通讯录里的号码 = 已知熟人。给 −20 的基础信任分（比陌生号的 +15 更重），
      // 因为「妈，把身份证号发给我，我帮你订票」这类家常请求本身句式和套信息高度相似，
      // 光靠词库分不开，只能用身份分兜底。实测这条原来被判 S3/high。
      // 只在"本来就有可疑分"时才打折，否则无命中短信会被扣成负数，脏了展示用的分数。
      if (score > 0) {
        score -= 20;
        hits.push({ word: '通讯录号码', weight: -20, tag: null });
      }
    }

    // ---------- 3.5 链接特征（与 App 端 Judge.java 逐行对齐） ----------
    // 钓鱼短信的核心动作是"让你点一个链接"。域名每天换、短链天天新，词库穷举不完，
    // 所以这里认的是"形式"而不是"内容"：正文里出现可点的网址就算钓鱼入口。
    // 实测：'您的ETC已停用，请点击 t.cn/Etc9 认证' 只给 55 分，
    // 因为骗子故意避开'点击链接'四个字 —— 补上形式特征才抓得住。
    if (URL_PATTERN.test(text)) {
      score += 20;
      hits.push({ word: '含链接', weight: 20, tag: 'S2' });
    }

    // ---------- 4. 验证码按发送方区别对待 ----------
    // 这条是实测踩出来的坑：原来统一 +10，导致银行正常验证码被判成预警。
    // 老人一天收三条验证码，每条都报警，子女三天就卸载了。
    if (isCode) {
      if (who === 'service') {
        hits.push({ word: '服务号验证码(+0)', weight: 0, tag: null });
      } else {
        // v3.4：+30 → +45。正规验证码只来自服务号；个人号码发来的"验证码"本身就够得上要钱阶段
        score += 45;
        hits.push({ word: '非服务号发验证码', weight: 45, tag: 'S4' });
      }
    }

    // ---------- 5. 官方宣传短信免打扰 ----------
    // 放在最后：先把分数和命中算全（留档给子女端看原因），再整体降级为 S0。
    const noticeWhy = this.noticeOf(text, who);
    if (noticeWhy) {
      return {
        score,
        hits,
        stage: 'S0',
        stageName: (this.stages.S0 || {}).name || '',
        level: 'none',
        exempt: 'publicity',
        why: `${noticeWhy}，判为官方宣传短信，不打扰`,
      };
    }

    const stage = this.stageOf(score, hits);
    let level = this.levelOf(score, stage);

    // 通讯录里的号码最多"提醒"，不盖屏 —— 除非已经明确走到要钱(S4)。
    // 老人手机里 80% 的短信来自家人，任何一条家常短信盖屏都会让产品被卸载。
    if (who === 'contact' && stage !== 'S4' && rank(level) > rank('watch')) {
      level = 'watch';
    }

    return {
      score,
      hits,
      stage,
      stageName: (this.stages[stage] || {}).name || '',
      level,
      why: hits.length
        ? hits.map(h => `${h.word}(${h.weight > 0 ? '+' : ''}${h.weight})`).join(' + ')
        : '无命中',
    };
  }

  /**
   * 阶段判定：分数 + 命中词 → 骗子走到第几步
   *
   * 【2026-11-04 模型修正】原实现是"总分落桶"：≥90 判 S4、≥70 判 S3、≥40 判 S2。
   * 被 A 线 30 条测试集证伪，判对率只有 57%；7 组阈值敏感性实验全部落在 53%~57%，
   * 证明问题不在阈值而在模型本身：
   *
   *   分数衡量的是"这条短信有多可疑"，阶段问的是"骗子走到第几步"——这是两件事。
   *   一条堆满「社保局+涉嫌+冻结+违规使用」的短信能凑 93 分被判成 S4（要钱），
   *   可它一分钱都没要；反过来「把验证码报给我」这种标准要钱短信只有 55 分，判成 S2。
   *
   * 新模型：**阶段由"命中了哪一阶段的词"说话，分数退化成最低门槛**。
   * 门槛只负责拦住"单个弱词就把阶段拉满"，不再携带任何阶段语义。
   *
   * @param {number} score 总分
   * @param {Array}  hits  命中明细（含 tag）。缺省时退回旧的落桶逻辑，保证外部调用不炸。
   */
  stageOf(score, hits) {
    // 兼容：没有命中明细时，仍按旧的分数落桶处理
    if (!Array.isArray(hits)) {
      for (const s of ['S4', 'S3', 'S2', 'S1']) {
        const need = (this.stages[s] || {}).need;
        if (need != null && score >= need) return s;
      }
      return 'S0';
    }

    // 1) 在命中词里挑出"走到的最远那一步"。反例词(tag=null)、负权重词不参与定阶段。
    let top = 'S0';
    let topRank = 0;
    for (const h of hits) {
      const r = STAGE_RANK[h.tag];
      if (!r || h.weight <= 0) continue;
      if (r > topRank) { topRank = r; top = h.tag; }
    }

    const gate = (s) => {
      const n = (this.stages[s] || {}).need;
      return n == null ? 0 : n;
    };

    // 2) 命中了某阶段的词，且分数够该阶段门槛 → 就是这一阶段
    if (top !== 'S0' && score >= gate(top)) return top;

    // 3) 命中了 S2 以上的话术，但分数没强到能定级（比如 S4 词命中了却只有 30 分）
    //    → 至少认定为"已进入话术流程"，退回 S1，不要当成无风险放过去。
    if (topRank > 1) return score >= gate('S1') ? 'S1' : 'S0';

    // 4) 只命中弱词，看够不够套近乎
    return score >= gate('S1') ? 'S1' : 'S0';
  }

  /**
   * 风险等级：产品行为的开关（盖不盖屏 / 推不推送 / 打不打电话 / 要不要调模型做摘要）
   *
   * 【2026-11-04 修正】原来纯按分数落桶，结果和阶段倒挂：
   *   A 线 30 条里 5 条"要钱"短信没有一条盖屏（3 条 warn、2 条 high），
   *   反倒 2 条"只是吓唬"的盖了屏。根因还是同一个——
   *   分数衡量"这条短信有多可疑"，不衡量"骗子走到哪一步"。
   *
   * 新规则：等级跟着阶段走。分数只在 S3 上做一次加严判断，
   * 因为"隔离"是本产品的核心观测点（老人答应不告诉家人之后，就没人能拦住）。
   *
   *   S4 要钱    → block  一律盖屏，骗子开始动钱没有商量余地
   *   S3 隔离    → 分数够高就 block，否则 high（最该介入，但不必每条都盖屏）
   *   S2 吓唬    → warn
   *   S1 套近乎  → watch
   *   S0         → none
   *
   * @param {number} score 总分
   * @param {string} stage 阶段。缺省时退回旧的分数落桶逻辑。
   */
  levelOf(score, stage) {
    const L = this.levels;
    // 兼容：没有阶段信息时仍按分数落桶
    if (!stage) {
      if (score >= L.block) return 'block';
      if (score >= L.high) return 'high';
      if (score >= L.warn) return 'warn';
      if (score >= L.watch) return 'watch';
      return 'none';
    }
    switch (stage) {
      case 'S4': return 'block';
      case 'S3': return score >= (L.high || 70) ? 'block' : 'high';
      // v3.4：同一阶段里信号叠得多，也要升级 —— 只按阶段定级会让
      //   "涉嫌洗钱+配合调查+公安局"（100 分、还在吓唬阶段）只得到一个普通提醒，
      //   "换手机号+猜猜我是谁+有急事"（多个弱信号）完全不提醒。
      case 'S2': return score >= (L.high || 70) ? 'high' : 'warn';
      case 'S1': return score >= (L.warn || 45) ? 'warn' : 'watch';
      default: return 'none';
    }
  }

  // ==========================================================================
  //  二、组合判据（场景级）
  // ==========================================================================

  /**
   * 根据当前会话状态判级。
   * 这是"电话场景"的核心：单看任何一条事件都不够，必须几条叠加。
   *
   * @param {object} st 状态快照
   * @returns {{level:string, why:string, hits:Array}|null}
   */
  judgeCombo(st) {
    // 先算短信分，组合判据可能引用它
    const smsScore = st.smsScore != null ? st.smsScore : 0;

    for (const c of this.combos) {
      if (!Array.isArray(c.all)) continue;
      if (c.all.every(expr => evalExpr(expr, st, smsScore))) {
        return {
          level: c.level,
          why: c.why || '',
          note: c.note || '',
          hits: c.all.slice(),
        };
      }
    }
    return null;
  }

  // ==========================================================================
  //  三、主入口：给定一个事件，算出该做什么
  // ==========================================================================

  /**
   * @param {string} type 事件类型：sms / call / app / install / a11y / notify
   * @param {object} data 事件数据
   * @param {object} st   当前会话状态（由 Guard 维护）
   */
  judge(type, data, st) {
    // 短信：先算分，再叠组合判据
    if (type === 'sms') {
      const r = this.scoreSms(data.body, data.who, data.isCode);
      // 暂时把这次得分塞进状态，供组合判据引用
      const st2 = Object.assign({}, st, { smsScore: r.score });
      const combo = this.judgeCombo(st2);
      if (combo && rank(combo.level) > rank(r.level)) {
        return { level: combo.level, why: combo.why, hits: r.hits, score: r.score, stage: r.stage };
      }
      return { level: r.level, why: r.why, hits: r.hits, score: r.score, stage: r.stage };
    }

    // 其他事件：直接走组合判据
    const combo = this.judgeCombo(st);
    if (combo) return combo;
    return { level: 'none', why: '无命中', hits: [] };
  }

  /**
   * 场景判级（供 Guard 调用）。
   *
   * 关键设计：不能直接用 judgeCombo。
   * 因为 combos 是按"从轻到重"排列的，一旦命中"通话满 5 分钟（watch）"就 return 了，
   * 后面的"打开银行（block）"根本走不到 —— 这是实测抓出来的 bug。
   *
   * 所以这里扫全部组合判据，取最重的那一条。
   * @param {object} st 状态快照
   */
  judgeScene(st) {
    let best = null;
    for (const c of this.combos) {
      if (!Array.isArray(c.all)) continue;
      const hit = c.all.every(expr => evalExpr(expr, st, st.smsScore || 0));
      if (!hit) continue;
      if (!best || rank(c.level) > rank(best.level)) {
        best = { level: c.level, why: c.why || '', note: c.note || '', hits: c.all.slice() };
      }
    }
    return best || { level: 'none', why: '', hits: [] };
  }
}

// ============================================================================
//  辅助函数
// ============================================================================

/** 等级排序，用来判断"有没有升级" */
function rank(level) {
  return { none: 0, watch: 1, warn: 2, high: 3, block: 4 }[level] || 0;
}

/**
 * 解析并求值一条条件表达式，形如：
 *   "call.who=stranger"   → 相等
 *   "call.dur>300"        → 大于
 *   "install.fromStore=false"
 *   "smsScore>=45"        → 引用短信得分
 */
function evalExpr(expr, st, smsScore) {
  const m = String(expr).match(/^\s*([\w.]+)\s*(>=|<=|>|<|=)\s*(.+?)\s*$/);
  if (!m) return false;
  const [, field, op, rawVal] = m;

  // 取实际值
  let actual;
  if (field === 'smsScore') {
    actual = smsScore;
  } else {
    actual = getPath(st, field);
  }
  if (actual === undefined || actual === null) {
    // 字段不存在时：判断"是否等于 false"，用于 fromStore 这类默认值
    if (op === '=' && rawVal === 'false') return true;
    return false;
  }

  const expect = parseVal(rawVal);

  switch (op) {
    case '=':  return looseEq(actual, expect);
    case '>':  return Number(actual) > Number(expect);
    case '<':  return Number(actual) < Number(expect);
    case '>=': return Number(actual) >= Number(expect);
    case '<=': return Number(actual) <= Number(expect);
    default:   return false;
  }
}

/** 按 "a.b.c" 取嵌套属性 */
function getPath(obj, path) {
  return String(path).split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

/** 把字符串字面量转成合适的类型 */
function parseVal(s) {
  if (s === 'true') return true;
  if (s === 'false') return false;
  if (s === 'null') return null;
  if (/^-?\d+(\.\d+)?$/.test(s)) return Number(s);
  return s.replace(/^["']|["']$/g, '');
}

function looseEq(a, b) {
  if (typeof a === 'boolean' || typeof b === 'boolean') return Boolean(a) === Boolean(b);
  return String(a) === String(b);
}

// ============================================================================
//  导出
// ============================================================================

/** 从文件加载规则（启动时调一次，读进内存） */
function loadRules(file) {
  const p = file || path.join(__dirname, '..', 'config', 'rules.json');
  const txt = fs.readFileSync(p, 'utf8');
  return JSON.parse(txt);
}

module.exports = { Judge, loadRules, rank, evalExpr, FORBIDDEN_FIELDS };
