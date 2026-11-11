'use strict';
/**
 * ============================================================================
 *  CaseBank.js —— 案例库 & 剧本工厂
 * ----------------------------------------------------------------------------
 *  它解决一个问题：演练不能永远是那 4 个写死的剧本。
 *
 *  素材有两个来源：
 *    ① 热点库 config/hot_topics.json —— 人工维护的近期高发类型
 *    ② 用户上传的诈骗信息（yd_report 表）—— /api/report 落库的那些
 *
 *  两路素材都先被拆成「骨架」，再重新组装成剧本：
 *
 *      素材 ──▶ 骨架(载体/身份/由头/施压/索要) ──▶ 变体 ──▶ 4 轮剧本
 *
 *  【为什么不直接复用原文】
 *    直接复用原文，老人练两遍就把台词背下来了，换个说法照样上当。
 *    拆成骨架后换掉外壳（冒充公检法 → 冒充社保局 → 冒充快递），
 *    施压逻辑一模一样，老人学到的才是「套路」而不是「标准答案」。
 *
 *  【四类身份，四套题库】
 *    冒充子女的场景，选项绝不能出现"你凭什么说我犯罪"。
 *    所以按 identity 归成 authority / service / kin / benefit 四类，
 *    每类一套专属的题目、正确应对、干扰项、告诫。
 *
 *  【跨套路混合（mode=mix）】
 *    不是把两种骗术随便揉。骗子真实的做法是「一层壳建信任，另一层壳收割」，
 *    所以这里固定了三种桥接范式，前两轮用 A 的外壳，后两轮换 B 的外壳，
 *    第 3 轮（隔离）正好是换壳的接缝——「这事别告诉家人，我帮你处理」。
 *    桥接必须与 A/B 的身份匹配，匹配不上就不混合，硬凑会演成四不像。
 *
 *  【判分仍然不交给模型】
 *    正确选项由 kind + stage + demand 查表得出，干扰项从同类的池里抽。
 *    模型只负责把台词说人话。
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------
//  一、身份 → 类别
// ---------------------------------------------------------------------------

/**
 * 四类身份决定了"骗子长什么样"和"老人该怎么应对"。
 * 权威类要报官号、服务类要回 App、亲属类要问私密、利益类要先问"要不要出钱"。
 */
const KIND_OF = {
  '公检法': 'authority',
  '社保局': 'authority',
  '银行风控': 'authority',
  '平台安全中心': 'authority',
  '贷款中介': 'authority',
  '陌生机构': 'authority',
  '电商客服': 'service',
  '快递公司': 'service',
  '活动主办方': 'service',
  '冒充亲属': 'kin',
  '子女': 'kin',
  '子女（视频出镜）': 'kin',
  '孙辈': 'kin',
  '领导': 'kin',
  '投资导师': 'benefit',
  '养老项目顾问': 'benefit',
  '兼职招募': 'benefit',
};

function kindOf(identity) {
  return KIND_OF[identity] || 'authority';
}

/** 电话里的自称，兜底台词要用 */
const SELF_CN = {
  '公检法': '市公安局刑侦支队',
  '社保局': '市社会保险事业管理局',
  '银行风控': '银行信用卡风控中心',
  '电商客服': '商城客服中心',
  '快递公司': '快递公司理赔部',
  '投资导师': '投资顾问',
  '兼职招募': '电商推广部',
  '领导': '领导',
  '平台安全中心': '平台安全中心',
  '贷款中介': '信贷服务中心',
  '活动主办方': '活动主办方',
  '陌生机构': '客服中心',
  '冒充亲属': '你的孩子',
  '子女': '你的孩子',
  '子女（视频出镜）': '你儿子',
  '孙辈': '你的孙子',
  '养老项目顾问': '康养产业集团客户部',
};

// ---------------------------------------------------------------------------
//  二、题库：按身份类别分开
//    right = 唯一的正确应对；traps = 干扰项（都是老人真实会说的话）
// ---------------------------------------------------------------------------

const LIB = {
  // 权威类：公检法 / 社保局 / 银行风控 / 平台安全 / 贷款中介
  authority: {
    1: {
      q: '对方自称是公安机关的',
      right: '挂了，我自己打 110 问',
      traps: ['是，我就是，什么事？', '你怎么知道我电话的？', '那你等一下，我叫儿子来听'],
      tip: '真警察不会打电话要你配合"资金核查"。挂掉，自己拨 110 问，是最稳的一步。',
    },
    2: {
      q: '对方说你涉嫌犯罪',
      right: '你凭什么说我犯罪？有文件吗？',
      traps: ['我没干过，那我该怎么办？', '我马上配合你们', '你能不能帮我说说情？', '我真没做，你们别抓我'],
      tip: '一被吓住就输了。真立案会有书面文书，不会在电话里通知你。',
    },
    3: {
      q: '对方要求你别告诉家人',
      right: '我现在就给我女儿打电话',
      traps: ['好，我不说', '那我先想想', '我儿子在外地，别打扰他'],
      tip: '"别告诉家人"是所有诈骗的共同点。凡是让你瞒着子女的，一定是骗子。',
    },
  },

  // 服务类：电商客服 / 快递 / 活动主办
  service: {
    1: {
      q: '对方说要给你赔钱',
      right: '我自己去 App 里看订单',
      traps: ['真的？怎么赔？', '那太好了', '要提供什么资料？'],
      tip: '主动给你钱的，九成是骗子。退款只会在你自己下单的 App 里办。',
    },
    2: {
      q: '对方说要先"验证资金"',
      right: '退款为什么要验我的余额？',
      traps: ['怎么验证？', '那你教我', '我卡里也没多少钱'],
      tip: '退款只需要你的账号，永远不会问你有多少钱。凡是问余额的，都是冲着钱来的。',
    },
    3: {
      q: '对方不让你挂电话',
      right: '我先挂了，问问我女儿',
      traps: ['好，你说', '那你快点', '我不挂，你继续'],
      tip: '"别挂电话、别问家人"是为了不给你冷静和商量的机会。挂断是安全的。',
    },
  },

  // 亲属类：冒充子女 / 孙辈 / 领导
  kin: {
    1: {
      q: '对方自称是你的孩子',
      right: '你叫什么名字？我儿子叫什么？',
      traps: ['哎，你怎么了？没事吧', '哦，那我存一下', '你怎么换号了？'],
      tip: '声音可以模仿，名字可以打听到。要问只有你家人知道的事。',
    },
    2: {
      q: '对方说自己出事了',
      right: '你先别急，我打你原来那个号',
      traps: ['要多少钱？妈给你', '你怎么这么不小心', '你在哪儿？我过去'],
      tip: '越是催你、越是不让你核实的，越可疑。挂掉，用原来的号码打回去。',
    },
    3: {
      q: '对方让你别告诉其他家人',
      right: '不行，这么大的事必须告诉你爸',
      traps: ['好，我不说，妈帮你', '那你自己小心点', '我先不告诉他'],
      tip: '骗子最怕你去问别人。只要对方要求保密，就先当他是骗子。',
    },
  },

  // 利益类：投资导师 / 兼职招募
  benefit: {
    1: {
      q: '对方说动动手就能赚钱',
      right: '不用出钱吧？',
      traps: ['有兴趣，怎么做？', '一天能赚多少？', '真的假的？'],
      tip: '"动动手就赚钱"本身就是最大的破绽。真工作不会在微信群里招人。',
    },
    2: {
      q: '对方让你先交钱升级',
      right: '要交钱就不做了',
      traps: ['多少钱？', '那交一点试试', '能不能先做免费的？'],
      tip: '所有刷单都是同一个套路：先给你甜头，再让你交本金。一交钱就再也拿不回来。',
    },
    3: {
      q: '对方让你瞒着家人',
      right: '我跟我儿子商量一下',
      traps: ['也是', '那我先不说', '等赚到了再告诉他们'],
      tip: '让你保密的人，一定不希望你被提醒。这本身就是最大的警报。',
    },
  },
};

/** 第 4 轮：正确应对（按类别 × 索要方式） */
const D4_RIGHT = {
  authority: {
    transfer: '不转，我先问家里人',
    code: '验证码不能给任何人',
    screen: '不装，也不共享屏幕',
    install: '不装，我去应用商店看看',
    invest: '不投了，这就是骗局',
  },
  service: {
    transfer: '不转，我自己去 App 里操作',
    code: '验证码不能给任何人',
    screen: '不共享屏幕，我自己弄',
    install: '不装，我去应用商店看看',
    invest: '不投了，这就是骗局',
  },
  kin: {
    transfer: '不转，我先视频看看你',
    code: '验证码不能给任何人',
    screen: '不共享屏幕',
    install: '不装，我去应用商店看看',
    invest: '不投了，这就是骗局',
  },
  benefit: {
    transfer: '不转了，这是骗局',
    code: '验证码不能给任何人',
    screen: '不共享屏幕',
    install: '不装，我去应用商店看看',
    invest: '不充了，这是骗局',
  },
};

/** 第 4 轮：干扰项（按类别 × 索要方式） */
const D4_TRAPS = {
  authority: {
    transfer: ['好，我这就去转', '那我先转一部分试试', '能不能少转点？'],
    code: ['好，是 382910', '我念慢一点你记', '我找不到短信，你等我一下'],
    screen: ['好，你教我怎么开', '我点了你发的链接了', '那我截图发给你看'],
    install: ['好，我装一下', '在哪里下载？'],
    invest: ['那我先投 2 万试试', '真的能赚这么多？'],
  },
  service: {
    transfer: ['好，我这就转', '那你把账号发我', '我卡里就 3 万'],
    code: ['好，是 382910', '我念慢一点', '是这个六位数吗？'],
    screen: ['好，你教我怎么开', '我点了你发的链接'],
    install: ['好，我装一下', '在哪里下载？'],
    invest: ['那我先充 2 万', '怎么充值？'],
  },
  kin: {
    transfer: ['好，妈这就给你转', '那我转 2 万行不行', '你等着，我去银行'],
    code: ['好，是 382910', '我念给你听'],
    screen: ['好，你说怎么弄'],
    install: ['好，我装一下'],
    invest: ['那我先给你 2 万'],
  },
  benefit: {
    transfer: ['好，我这就转', '那我先转一点'],
    code: ['好，是 382910', '我念慢一点'],
    screen: ['好，你教我'],
    install: ['好，我装一下'],
    invest: ['那我先投 2 万试试', '真的能赚这么多？', '我加你微信，怎么充值？'],
  },
};

/** 第 4 轮的提问，按索要方式区分 */
const DEMAND_Q = {
  transfer: '对方让你把钱转到"安全账户"',
  code: '对方要你念短信验证码',
  screen: '对方要你共享屏幕 / 装个检测软件',
  install: '对方让你下载一个不明 App',
  invest: '对方让你充值投资',
};

/** 第 4 轮的通用告诫 */
const DEMAND_TIP = {
  transfer: '根本不存在"安全账户"。只要听到转账、验资、审查资金，就是诈骗。',
  code: '验证码等于你银行卡的钥匙。任何人问你要，无论什么身份，都是骗子。',
  screen: '屏幕共享等于把手机交给对方。真正的客服、警察、银行都不会要你共享屏幕。',
  install: '只要不是从手机自带应用商店装的，都别装。装了就等于把手机交出去。',
  invest: '任何承诺"稳赚不赔"的都是骗局。真能稳赚，他自己早发财了，不会告诉你。',
};

// ---------------------------------------------------------------------------
//  三、脱敏
// ---------------------------------------------------------------------------

/**
 * 把案例里的敏感信息打码。上传的短信里常带老人自己的手机号、卡号，
 * 这些不能原样存进演练素材库，也不能发给模型。
 */
function mask(text) {
  let s = String(text || '');
  // 手机号
  s = s.replace(/(?<!\d)(1[3-9]\d)(\d{4})(\d{4})(?!\d)/g, '$1****$3');
  // 身份证（18 位，末位可能 X）
  s = s.replace(/(?<!\d)(\d{6})(\d{8})(\d{3}[\dXx])(?!\d)/g, '$1********$3');
  // 银行卡（16~19 位连续数字）
  s = s.replace(/(?<!\d)(\d{4})(\d{8,11})(\d{4})(?!\d)/g, '$1 **** **** $3');
  // 快递单号 / 长串数字压成占位
  s = s.replace(/(?<!\d)(\d{12,})(?!\d)/g, '［单号已隐去］');
  return s;
}

// ---------------------------------------------------------------------------
//  四、上报记录 → 骨架
// ---------------------------------------------------------------------------

/**
 * 模型在上传识别时已经给了 type 字段（如"冒充公检法"）。
 * 这里用关键词把 type 映射回骨架，比再调一次模型快得多、也便宜得多。
 * 顺序有讲究：公检法要排在"客服"前面，
 * 因为"冒充公安说你是客服泄露的"这种混合描述里，公检法才是主身份。
 */
const TYPE_MAP = [
  { kw: ['公检法', '公安', '警察', '检察', '法院', '洗钱', '涉案', '通缉', '逮捕'], identity: '公检法', demand: 'transfer', carrier: 'call' },
  { kw: ['社保', '医保', '公积金', '补贴'], identity: '社保局', demand: 'transfer', carrier: 'sms' },
  { kw: ['远程', '屏幕共享', '共享屏幕', '安全软件', '木马'], identity: '平台安全中心', demand: 'screen', carrier: 'call' },
  { kw: ['银行', '信用卡', '境外消费', '风控'], identity: '银行风控', demand: 'code', carrier: 'sms' },
  { kw: ['客服', '退款', '理赔', '订单'], identity: '电商客服', demand: 'code', carrier: 'call' },
  { kw: ['快递', '包裹', '物流'], identity: '快递公司', demand: 'install', carrier: 'sms' },
  { kw: ['投资', '理财', '基金', '股票', '荐股', '内幕', '养老项目'], identity: '投资导师', demand: 'invest', carrier: 'wechat' },
  { kw: ['刷单', '兼职', '佣金', '返利', '做任务'], identity: '兼职招募', demand: 'invest', carrier: 'wechat' },
  { kw: ['子女', '儿子', '女儿', '孙', '换号', '亲属', '家人'], identity: '冒充亲属', demand: 'transfer', carrier: 'call' },
  { kw: ['领导', '老板', '同事', '上级'], identity: '领导', demand: 'transfer', carrier: 'wechat' },
  { kw: ['中奖', '红包', '抽奖', '免费领'], identity: '活动主办方', demand: 'code', carrier: 'sms' },
  { kw: ['贷款', '征信', '额度', '借款'], identity: '贷款中介', demand: 'transfer', carrier: 'call' },
];

const DEFAULT_SKELETON = {
  carrier: 'call', identity: '陌生机构', demand: 'transfer',
  bait: '（未识别出具体由头）', pressure: ['恐吓'],
};

/** 从一条上报记录推出骨架 */
function skeletonFromReport(r) {
  const hay = [r.type, r.reason, r.excerpt].filter(Boolean).join(' ');
  const hit = TYPE_MAP.find(m => m.kw.some(k => hay.includes(k)));
  const base = hit
    ? { carrier: hit.carrier, identity: hit.identity, demand: hit.demand, bait: '', pressure: [] }
    : { ...DEFAULT_SKELETON };
  return {
    ...base,
    // 由头用模型给的理由，脱敏后截断——这是"来自你上传的那条"最直观的痕迹
    bait: mask(r.reason || r.excerpt || '').slice(0, 40) || base.bait,
    pressure: inferPressure(hay),
    caseRef: {
      id: r.id,
      at: r.createdAt || r.created_at || '',
      excerpt: mask(r.excerpt || '').slice(0, 60),
      type: r.type || '',
      stage: r.stage,
    },
  };
}

/** 从文本里推断施压手法 */
function inferPressure(hay) {
  const p = [];
  if (/别告诉|不要告诉|保密|涉密|串供|别跟/.test(hay)) p.push('保密');
  if (/马上|立刻|今天|小时|限时|来不及|最后/.test(hay)) p.push('限时');
  if (/抓|坐牢|冻结|追究|立案|处罚|停用/.test(hay)) p.push('恐吓');
  if (/名额|最后|仅剩|错过/.test(hay)) p.push('稀缺');
  if (/大家|群里|别人都|都在赚/.test(hay)) p.push('从众');
  return p.length ? p : ['恐吓'];
}

// ---------------------------------------------------------------------------
//  五、跨套路桥接
// ---------------------------------------------------------------------------

/**
 * 骗子真实的"两层壳"打法，三种范式。
 * match(kindA, kindB) 决定这对组合能不能这么接——接不上就不混合。
 *
 * 这几条 match 不是拍脑袋：它们是真实案件里出现过的组合方式。
 *   先服务后执法：冒充客服说你的账户被用于犯罪 → 转接"公安"
 *   先给甜头后收割：先返 8 块佣金 → 引到投资平台
 *   先恐慌再当救星：冒充子女出事 → "我能帮你摆平"的人登场
 */
const BRIDGES = [
  {
    key: 'trust-authority',
    name: '先服务后执法',
    how: '先用客服、快递这类日常服务建立信任，等老人开始配合操作，再抛出"你的账户被用于犯罪"，身份升级成公检法。',
    reason: '信任一旦建立，后面的恐吓杀伤力翻倍——老人会想「他刚才帮我退款，应该是好人」。',
    match: (a, b) => (a === 'service' || a === 'benefit') && b === 'authority',
  },
  {
    key: 'bait-harvest',
    name: '先给甜头后收割',
    how: '先用刷单佣金、红包、免费礼品让老人真拿到几十块，再引到投资或充值平台，把本金吞掉。',
    reason: '真拿到过钱的人不会相信自己被骗，这是杀猪盘的核心机制。',
    match: (a, b) => (a === 'benefit' || a === 'service') && b === 'benefit',
  },
  {
    key: 'panic-rescue',
    name: '先制造恐慌再当救星',
    how: '先冒充子女或孙辈说自己出了事，把老人吓住，再让"能帮忙摆平的人"登场，以打点费、保证金名义收钱。',
    reason: '恐慌状态下人只想赶紧解决，不会去核实第二个人的身份。',
    match: (a, b) => a === 'kin' && (b === 'authority' || b === 'service'),
  },
  {
    key: 'authority-relay',
    name: '层层转接放大权威',
    how: '先冒充次一级机构（通信管理局、社保中心、银行风控）说你涉及某个案子，再"主动帮你转接"到更高级别的办案单位，越转越吓人。',
    reason: '"我帮您转接"这个动作会让老人觉得对方在帮忙，警惕心反而下降；转接后换成更权威的身份，之前的怀疑被清空重来。',
    match: (a, b) => a === 'authority' && b === 'authority',
  },
];

/** 权威类内部的"级别"，转接型诈骗必须从低往高演 */
const AUTH_RANK = {
  '社保局': 1, '银行风控': 1, '平台安全中心': 1, '贷款中介': 1, '陌生机构': 1,
  '公检法': 3,
};

/** 按 A/B 的类别挑一个接得上的桥接；都不匹配返回 null */
function pickBridge(kindA, kindB, wanted) {
  if (wanted) {
    const b = BRIDGES.find(x => x.key === wanted);
    if (b && b.match(kindA, kindB)) return b;
  }
  return BRIDGES.find(b => b.match(kindA, kindB)) || null;
}

// ---------------------------------------------------------------------------
//  六、热点库
// ---------------------------------------------------------------------------

let HOT_CACHE = null;

function loadHotTopics(file) {
  if (HOT_CACHE) return HOT_CACHE;
  const p = file || path.join(__dirname, '..', 'config', 'hot_topics.json');
  try {
    const raw = JSON.parse(fs.readFileSync(p, 'utf8'));
    HOT_CACHE = (raw.topics || []).map(t => ({
      id: t.id, name: t.name, icon: t.icon || '⚠️',
      hot: Number(t.hot) || 50, since: t.since || '', note: t.note || '',
      skeleton: t.skeleton || DEFAULT_SKELETON,
      origin: 'hot',
    }));
  } catch (e) {
    console.warn('[CaseBank] 热点库读取失败：', e.message);
    HOT_CACHE = [];
  }
  return HOT_CACHE;
}

function hotList() {
  return loadHotTopics().map(t => ({
    id: t.id, name: t.name, icon: t.icon, hot: t.hot, since: t.since, note: t.note,
    demand: t.skeleton.demand, carrier: t.skeleton.carrier, identity: t.skeleton.identity,
    kind: kindOf(t.skeleton.identity),
  })).sort((a, b) => b.hot - a.hot);
}

// ---------------------------------------------------------------------------
//  七、抽题
// ---------------------------------------------------------------------------

/** 按热度加权抽一个热点，avoid 里的 id 不抽 */
function pickHot(avoid = []) {
  const pool = loadHotTopics().filter(t => !avoid.includes(t.id));
  if (!pool.length) return null;
  const total = pool.reduce((s, t) => s + t.hot, 0);
  let r = Math.random() * total;
  for (const t of pool) {
    r -= t.hot;
    if (r <= 0) return t;
  }
  return pool[pool.length - 1];
}

/** 从上报记录里抽一条诈骗案例 */
function pickCase(reports = [], avoid = []) {
  const pool = reports
    .filter(r => r && r.isFraud !== false && r.is_fraud !== 0 && r.is_fraud !== false)
    .filter(r => !avoid.includes(r.id));
  if (!pool.length) return null;
  const weighted = pool.map((r, i) => ({ r, w: Math.max(1, pool.length - i) }));
  const total = weighted.reduce((s, x) => s + x.w, 0);
  let v = Math.random() * total;
  for (const x of weighted) {
    v -= x.w;
    if (v <= 0) return x.r;
  }
  return weighted[weighted.length - 1].r;
}

// ---------------------------------------------------------------------------
//  八、蓝图组装
// ---------------------------------------------------------------------------

/**
 * 生成一局的剧本蓝图。Drill 拿它让模型写台词、按表出选项。
 *
 * opts:
 *   mode      'hot' | 'case' | 'mix' | 'auto'
 *   hotId     指定热点
 *   reportId  指定案例
 *   avoid     上局用过的 id
 *   bridge    指定桥接范式 key（接不上会自动换一个）
 */
function buildBlueprint(opts = {}, reports = []) {
  const avoid = opts.avoid || [];
  let mode = opts.mode || 'auto';
  if (mode === 'auto') {
    // 没有案例就热点，有案例就一半概率用案例——保证"上传过的东西会被用上"
    if (reports.length && Math.random() < 0.5) mode = 'mix';
    else mode = 'hot';
  }
  if (!['hot', 'case', 'mix'].includes(mode)) mode = 'hot';

  let caseA = null;
  if (mode === 'case' || mode === 'mix') {
    caseA = opts.reportId
      ? reports.find(r => String(r.id) === String(opts.reportId)) || pickCase(reports, avoid)
      : pickCase(reports, avoid);
  }

  let hotA = null;
  if (!caseA) {
    hotA = opts.hotId
      ? loadHotTopics().find(t => t.id === opts.hotId) || pickHot(avoid)
      : pickHot(avoid);
  }

  // 种子 A
  let seedA;
  if (caseA) {
    const sk = skeletonFromReport(caseA);
    seedA = {
      origin: 'case',
      name: (caseA.type || '你上传的案例'),
      icon: '📮',
      skeleton: sk,
      caseRef: sk.caseRef,
      // 这条案例是"老人自己传上来的"还是"挂断后录音复盘自动记下的"，文案不一样。
      // 自动上报那种情况里她没有做过"上传"这个动作，说成"你上传的那条"就是在编事实；
      // 老人听不出来，但子女端和答辩时被问一句就穿帮。
      byCall: caseA.reporter === 'callaudio',
    };
  } else if (hotA) {
    seedA = { origin: 'hot', name: hotA.name, icon: hotA.icon, skeleton: hotA.skeleton, hotRef: { id: hotA.id, name: hotA.name } };
  } else {
    seedA = {
      origin: 'hot', name: '冒充公检法', icon: '👮',
      skeleton: { carrier: 'call', identity: '公检法', bait: '你的银行卡涉嫌洗钱', pressure: ['恐吓', '保密'], demand: 'transfer' },
    };
  }

  // 混合：再加一个 B，并且桥接必须接得上
  let seedB = null;
  let bridge = null;
  if (mode === 'mix') {
    const kindA = kindOf(seedA.skeleton.identity);
    const used = [seedA.hotRef && seedA.hotRef.id, seedA.caseRef && seedA.caseRef.id].filter(Boolean).concat(avoid);

    // 多抽几个候选，挑第一个能和 A 接上的
    const cands = [];
    const tried = new Set();
    for (let i = 0; i < 6; i++) {
      const t = pickHot(used.concat([...tried]));
      if (!t) break;
      tried.add(t.id);
      cands.push(t);
    }
    // 先找类别不同的（跨身份才有训练价值），找不到再接受同类转接
    // 但 B 的机构不能和 A 完全一样，否则等于没换壳
    const isSameActor = (t) => t.skeleton.identity === seedA.skeleton.identity || t.name === seedA.name;
    for (const t of cands) {
      if (isSameActor(t) || kindOf(t.skeleton.identity) === kindA) continue;
      const b = pickBridge(kindA, kindOf(t.skeleton.identity), opts.bridge || null);
      if (b) { seedB = t; bridge = b; break; }
    }
    if (!seedB) {
      for (const t of cands) {
        if (isSameActor(t)) continue;
        const b = pickBridge(kindA, kindOf(t.skeleton.identity), null);
        if (b) { seedB = t; bridge = b; break; }
      }
    }
    if (!seedB) mode = seedA.origin === 'case' ? 'case' : 'hot';   // 接不上就不硬凑
  }

  return compose(seedA, seedB, bridge, mode);
}

/** 把种子组装成 4 轮蓝图 */
function compose(seedA, seedB, bridge, mode) {
  let skA = seedA.skeleton || DEFAULT_SKELETON;
  let skB = seedB ? (seedB.skeleton || DEFAULT_SKELETON) : null;

  /**
   * 转接型诈骗的叙事是「低一级机构 → 帮你转接 → 高一级机构」。
   * 如果种子里高级的先出场（比如案例是公检法、热点是社保局），必须交换，
   * 否则会演成"公安局说自己要转接给社保局"，逻辑倒挂、一眼假。
   */
  if (bridge && bridge.key === 'authority-relay' && skB) {
    const ra = AUTH_RANK[skA.identity] || 1;
    const rb = AUTH_RANK[skB.identity] || 1;
    if (ra > rb) { const t = skA; skA = skB; skB = t; }
  }

  const demand = skB ? skB.demand : skA.demand;
  const idA = skA.identity;
  const idB = skB ? skB.identity : skA.identity;

  const lanes = [
    { stage: 1, identity: idA, intent: '套近乎、立身份', carrier: skA.carrier, kind: kindOf(idA) },
    { stage: 2, identity: idA, intent: '抛由头、制造紧张', carrier: skA.carrier, kind: kindOf(idA) },
    { stage: 3, identity: idB, intent: '隔离、要求别告诉家人', carrier: (skB && skB.carrier) || skA.carrier, kind: kindOf(idB) },
    { stage: 4, identity: idB, intent: '索要', carrier: (skB && skB.carrier) || skA.carrier, kind: kindOf(idB) },
  ];

  const name = seedB ? `${seedA.name} × ${seedB.name}` : seedA.name;

  return {
    mode,
    name,
    icon: seedB ? '🎭' : seedA.icon,
    demand,
    lanes,
    brief: buildBrief(seedA, seedB, bridge, skA, skB, demand),
    source: {
      mode,
      seedA: { origin: seedA.origin, name: seedA.name, ref: seedA.hotRef || seedA.caseRef || null },
      seedB: seedB ? { origin: 'hot', name: seedB.name, ref: { id: seedB.id, name: seedB.name } } : null,
      bridge: bridge ? { key: bridge.key, name: bridge.name, how: bridge.how, why: bridge.reason } : null,
    },
    from: seedB
      ? `${seedB.name}（热点）叠加 ${seedA.name}`
      : seedA.origin === 'case'
        ? (seedA.byCall
            ? `上次那通电话的套路「${seedA.name}」`
            : `你上传的那条「${seedA.name}」`)
        : `${seedA.name}（本周热点）`,
  };
}

/**
 * 拼给模型的说明。模型只按这个写台词，不参与判分。
 * 关键：身份切换的那一轮，由头也要跟着换，否则会出现
 * 「派出所民警说孙子出事了」这种自相矛盾的台词。
 */
function buildBrief(seedA, seedB, bridge, skA, skB, demand) {
  const who = (id) => SELF_CN[id] || id;
  const p = [];

  if (seedB && bridge) {
    if (bridge.key === 'authority-relay') {
      // 转接型要写清"谁把电话交给谁"，否则模型会一直用第一个身份说话
      p.push(
        `这是一局"层层转接"的诈骗。${bridge.how}`,
        `第1轮：你是${who(skA.identity)}，套近乎、拉近关系；`,
        `第2轮：还是${who(skA.identity)}，抛出「${skA.bait || '对方涉嫌一个案子'}」，说这事很严重；`,
        `第3轮：你说"我帮您转接到${who(skB.identity)}那边"，然后换成${who(skB.identity)}的口吻，要求对方别告诉家人；`,
        `第4轮：以${who(skB.identity)}的身份，${demandCN(demand)}。`
      );
    } else {
      p.push(
        `这是一局"两层壳"诈骗（${bridge.name}）。${bridge.how}`,
        `第1轮：你是${who(skA.identity)}，套近乎、拉近关系；`,
        `第2轮：还是${who(skA.identity)}，抛出「${skA.bait || '对方出事了'}」，制造紧张；`,
        `第3轮：换成${who(skB.identity)}，用"这事我帮您处理""您别跟家里人说"过渡，要求对方保密；`,
        `第4轮：以${who(skB.identity)}的身份，${demandCN(demand)}。`
      );
    }
  } else {
    p.push(
      `诈骗手法：${seedA.name}。载体是${carrierCN(skA.carrier)}。`,
      `全程你都是${who(skA.identity)}。`,
      `第1轮：套近乎，说清自己是谁；`,
      `第2轮：抛出「${skA.bait || '对方出事了'}」，制造紧张；`,
      `第3轮：要求对方别告诉家人；`,
      `第4轮：${demandCN(demand)}。`
    );
  }
  p.push('第4轮只做一件事：要么要转账，要么要验证码，不要混在一起。');
  return p.join('');
}

function carrierCN(c) {
  return ({
    call: '电话', sms: '短信', wechat: '微信', video: '视频通话', app: 'App 推送',
    'offline+wechat': '线下接触后转微信', 'sms+wechat': '短信后加微信',
  })[c] || '电话';
}

function demandCN(d) {
  return ({
    transfer: '要对方把钱转到"安全账户"或指定银行卡',
    code: '要对方念出刚收到的短信验证码',
    screen: '要对方共享屏幕、或装一个"安全检测"软件',
    install: '要对方下载一个不明 App 并给权限',
    invest: '要对方往一个平台充钱投资',
  })[d] || '要钱';
}

// ---------------------------------------------------------------------------
//  九、出题（选项）
// ---------------------------------------------------------------------------

/** 从数组里抽 n 个不重复的 */
function sample(arr, n) {
  const a = arr.slice();
  const out = [];
  while (a.length && out.length < n) {
    out.push(a.splice(Math.floor(Math.random() * a.length), 1)[0]);
  }
  return out;
}

/**
 * 按 lane 生成一轮的题目与选项。
 * 正确项永远来自查表，干扰项来自同类的池 —— 模型改不了对错。
 */
function buildRound(lane) {
  const kind = lane.kind || kindOf(lane.identity);
  const stage = lane.stage;
  const demand = lane.demand || 'transfer';

  let question, tip, right, trapPool;

  if (stage === 4) {
    question = DEMAND_Q[demand] || DEMAND_Q.transfer;
    tip = DEMAND_TIP[demand] || DEMAND_TIP.transfer;
    const R = D4_RIGHT[kind] || D4_RIGHT.authority;
    const T = D4_TRAPS[kind] || D4_TRAPS.authority;
    right = R[demand] || R.transfer;
    trapPool = T[demand] || T.transfer || T.invest || [];
  } else {
    const L = (LIB[kind] || LIB.authority)[stage] || LIB.authority[1];
    question = L.q;
    tip = L.tip;
    right = L.right;
    trapPool = L.traps;
  }

  const traps = sample(trapPool, 2);
  const choices = [{ t: right, ok: true }, ...traps.map(t => ({ t, ok: false }))];

  // 打乱，正确项不能总在同一个位置
  for (let i = choices.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [choices[i], choices[j]] = [choices[j], choices[i]];
  }

  return { stage, identity: lane.identity, kind, question, choices, tip };
}

module.exports = {
  mask,
  kindOf,
  SELF_CN,
  skeletonFromReport,
  loadHotTopics,
  hotList,
  pickHot,
  pickCase,
  buildBlueprint,
  buildRound,
  pickBridge,
  BRIDGES,
  LIB,
  D4_RIGHT,
  D4_TRAPS,
  DEMAND_Q,
  DEMAND_TIP,
  carrierCN,
  demandCN,
};
