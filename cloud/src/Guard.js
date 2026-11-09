'use strict';
/**
 * ============================================================================
 *  Guard.js —— 核心调度器
 * ----------------------------------------------------------------------------
 *  所有事件（短信、通话、前台应用、安装、无障碍）都汇到这里，流程固定四步：
 *
 *      ① 取状态   →   ② 判级   →   ③ 去重（不升级就不吭声）   →   ④ 干预
 *
 *  第 ③ 步是整个产品能不能被用下去的关键：
 *    通话时长每秒都在变，如果每次都推，子女手机一天几百条。
 *    加了"只在升级时说话"之后，一次诈骗全过程只会推 3~4 条。
 * ============================================================================
 */

const { rank } = require('./Judge');

// 等级 → 老人端应该看到的界面状态
const UI = {
  none:  { badge: '守护中',  cls: 'ok',    shield: false, speak: null },
  watch: { badge: '留意中',  cls: 'l1',    shield: false, speak: null },
  warn:  { badge: '请注意',  cls: 'l3',    shield: false, speak: null },
  high:  { badge: '高度可疑', cls: 'l4',   shield: true,  speak: 'stop' },
  block: { badge: '已拦截',  cls: 'l5',    shield: true,  speak: 'stop' },
};

class Guard {
  /**
   * @param {object} deps { judge, state, db, pangu, onPush }
   */
  constructor(deps) {
    this.judge = deps.judge;
    this.state = deps.state;
    this.db = deps.db;
    this.pangu = deps.pangu;
    this.onPush = deps.onPush || (() => {});   // 推送回调（WebSocket / SSE）
    this.lastRank = new Map();                  // elderId → 上次的等级，用于去重
  }

  /**
   * 处理一条事件。这是整个系统的唯一入口。
   * @param {string} elderId
   * @param {string} type  sms / call / app / install / a11y / notify / known
   * @param {object} data
   */
  async handle(elderId, type, data = {}) {
    let st = await this.state.get(elderId);

    // ---------- ① 用事件更新状态 ----------
    st = applyEvent(st, type, data);
    await this.state.set(elderId, st);

    // ---------- ② 判级 ----------
    let r;
    if (type === 'sms') {
      r = this.judge.judge('sms', data, st);
      // 短信本身的分级可能低于当前场景级（比如正在通话中开银行时来了一条验证码），
      // 所以再叠一次场景判级，取重的那个
      const scene = this.judge.judgeScene(Object.assign({}, st, { smsScore: r.score || 0 }));
      if (rank(scene.level) > rank(r.level)) {
        r = { level: scene.level, why: scene.why, hits: r.hits || [], score: r.score, stage: r.stage };
      }
    } else {
      r = this.judge.judgeScene(st);
    }

    // 熟人标记不参与判级，只在这里统一兜底一次，防止有人误写进 combos
    // （这条是"点认识不能关掉防护"的代码级保障）

    // ---------- ③ 去重：没升级就什么都不做 ----------
    //  规则：等级升高 → 必推。
    //        等级不变，但这是"新的危险动作"（如已盖屏后又来验证码）→ 也要推，
    //        否则子女端会漏掉"她收到验证码了"这条最关键的信息。
    const prev = this.lastRank.get(elderId) || 0;
    const cur = rank(r.level);
    const isUpgrade = cur > prev;
    const isNewAction = cur === prev && cur >= 2 && type !== 'call';   // warn 及以上，且不是通话计时
    if (cur > 0) this.lastRank.set(elderId, cur);

    // ---------- ④ 落库（无论是否升级都记录，形成完整流水）----------
    await this.db.addEvent({
      elderId, type,
      code: data.code || type,
      level: r.level,
      score: r.score || 0,
      why: r.why || '',
      payload: data,
    });

    if (!isUpgrade && !isNewAction) {
      return { level: r.level, ui: UI[r.level] || UI.none, pushed: false, why: r.why };
    }

    // ---------- ⑤ 干预 ----------
    const ui = UI[r.level] || UI.none;
    const title = makeTitle(type, data, r);
    const body = makeBody(type, data, r);

    // 记下这条卡片的库内 id —— 摘要生成后要按 id 回填，否则刷新就丢
    const alertId = await this.db.addAlert({
      elderId, level: r.level, title, body, why: r.why,
      channel: r.level === 'block' || r.level === 'high' ? 'push+call' : 'push',
    });

    // 大模型只在阻断级做"人话摘要"。
    // 【铁律】绝不 await：阻断卡必须立刻推给子女（模型可能要 5 秒），
    // 摘要生成后作为独立消息补发，前端收到后更新卡片。
    // —— 之前这里写的是 await，阻断卡会卡 12 秒等模型，违反 L2 不拖 L1 的原则。
    if (r.level === 'block' && this.pangu && this.pangu.enabled) {
      const lines = this.recentLines(st, data);
      const name = data.elderName;
      this.pangu.explain(lines, name).then(async (s) => {
        if (!s) return;
        // 先落库，再推送。顺序不能反：
        //   只推送不落库 = 子女端刷新一下，AI 说的话就没了（这是之前的缺陷）
        await this.db.setAlertSummary(alertId, s);
        this.onPush(elderId, { type: 'summary', level: r.level, alertId, summary: s, time: hhmm() });
      }).catch(() => {}); // 失败静默：摘要只是锦上添花
    }

    const push = {
      type: 'alert',
      alertId,
      level: r.level,
      title,
      body,
      summary: null,     // 摘要稍后由 type:'summary' 消息补上，可能永远不来（模型失败）
      why: r.why,
      /* channel 以前只写进库、没有跟着推出去，前端只能靠"猜不到就当通话"兜底。
         补上之后，子女端分「通话 / 短信」两栏才是按数据走的，不是按默认值猜的。 */
      channel: r.level === 'block' || r.level === 'high' ? 'push+call' : 'push',
      ui,
      time: hhmm(),
    };
    this.onPush(elderId, push);

    return { level: r.level, ui, pushed: true, alert: push, why: r.why };
  }

  /** 把当前状态翻译成几句话，喂给模型做摘要 */
  recentLines(st, data) {
    const L = [];
    if (st.call.active) {
      L.push(`${st.call.who === 'stranger' ? '陌生号码' : '通讯录号码'}来电，已通话 ${Math.floor(st.call.dur / 60)} 分钟`);
    }
    if (st.app.isBank) L.push('打开了手机银行');
    if (data.isCode) L.push('收到银行验证码短信');
    if (st.install.fromStore === false) L.push('安装了应用商店之外的软件');
    if (st.a11y.granted) L.push('给该软件开启了无障碍权限');
    return L;
  }

  /** 重置一场演示 */
  async reset(elderId) {
    await this.state.clear(elderId);
    this.lastRank.delete(elderId);
  }

  /** 熟人标记：只影响"要不要弹询问"，绝不参与判级 */
  async markKnown(elderId, minutes = 15) {
    const st = await this.state.get(elderId);
    st.known = {
      marked: true,
      at: Date.now(),
      until: Date.now() + minutes * 60000,
    };
    await this.state.set(elderId, st);
    // 注意：这里不修改 lastRank，也不撤销已发出的任何预警
    return st.known;
  }
}

// ============================================================================
//  事件 → 状态
// ============================================================================

function applyEvent(st, type, d) {
  switch (type) {
    case 'call':
      if (d.state === 'ringing') {
        st.call = { active: false, number: d.number || '', who: d.who || '', direction: 'in', dur: 0, startAt: Date.now(), rejectFamily: 0 };
      } else if (d.state === 'active') {
        st.call.active = true;
        st.call.number = d.number || st.call.number;
        st.call.who = d.who || st.call.who;
        if (!st.call.startAt) st.call.startAt = Date.now();
      } else if (d.state === 'end') {
        // 挂断 = 这一场风险窗口关闭，必须整组清掉。
        // 只把 active 置 false 是不够的：dur / who 留着的话，
        // "call.who=stranger && call.dur>480" 在挂断那一刻仍然成立，
        // 老人端会一直挂着"请注意"，子女端还会补推一条"通话很久了"。
        // （实测抓到的：挂断后仍返回 warn）
        st.call = { active: false, number: '', who: '', direction: 'in', dur: 0, startAt: 0, rejectFamily: 0 };
        st.app = { pkg: '', label: '', isBank: false };
      }
      if (d.state !== 'end') {
        if (d.dur != null) st.call.dur = Number(d.dur);
        if (d.rejectFamily != null) st.call.rejectFamily = Number(d.rejectFamily);
      }
      break;

    case 'sms':
      st.sms = { lastBody: d.body || '', lastAt: Date.now(), isCode: !!d.isCode };
      break;

    case 'app':
      st.app = { pkg: d.pkg || '', label: d.label || '', isBank: !!d.isBank };
      break;

    case 'install':
      st.install = { fromStore: d.fromStore !== false, pkg: d.pkg || '' };
      break;

    case 'a11y':
      st.a11y = { granted: !!d.granted };
      break;

    default:
      break;
  }
  return st;
}

// ============================================================================
//  文案
// ============================================================================
//  两条铁律：
//    老人端 —— 永远不说"诈骗"两个字。她不信软件，她只信事实。
//    子女端 —— 不复述"发生了什么"，而是说"你该做什么"。

function makeTitle(type, d, r) {
  const N = d.elderName || '妈妈';   // 称谓由上报方给（父亲/母亲/奶奶…），不写死
  if (r.level === 'block') {
    if (d.isCode) return `${N}收到银行验证码，但看不到内容`;
    if (d.isBank || (r.why || '').includes('银行')) return `${N}正在操作手机银行`;
    return `${N}那边出事了`;
  }
  if (r.level === 'high') return `${N}装了一个来路不明的软件`;
  if (r.level === 'warn') return `${N}和一个陌生号码通话很久了`;
  if (r.level === 'watch') return `${N}接到一个陌生号码来电`;
  return `${N}那边有动静`;
}

function makeBody(type, d, r) {
  // 性别中立：老人可能是父亲也可能是母亲，别写"她"
  if (r.level === 'block') {
    if (d.isCode) return '验证码念不出来，对方也拿不到。这通电话现在打过去最有用。';
    return '屏幕已经盖住。这通电话现在打过去，一定能接。';
  }
  if (r.level === 'high') return '这个软件有远程控制手机的能力。建议现在就打个电话。';
  if (r.level === 'warn') return '号码不在通讯录里，通话已经超过 8 分钟。';
  if (r.level === 'watch') return '号码不在通讯录里，我们开始留意。';
  return r.why || '';
}

function hhmm() {
  const d = new Date();
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

module.exports = { Guard, UI };
