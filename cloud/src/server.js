'use strict';
/**
 * ============================================================================
 *  server.js —— 银盾云端服务入口
 * ----------------------------------------------------------------------------
 *  职责：
 *    1. 托管演示页面（public/）
 *    2. 提供事件上报接口 POST /api/event
 *    3. 提供子女端实时推送 SSE /api/stream
 *    4. 提供规则热更新 /api/rules（A 线改完立刻生效，不用重新部署）
 *
 *  一条原则：任何外部依赖（RDS / Redis / 盘古）挂了，服务本身不能挂。
 *  演示现场断网也要能跑完整流程。
 * ============================================================================
 */

const path = require('path');
const fs = require('fs');
require('./env'); // 加载 .env（Key 等敏感配置，已排除出仓库）
const express = require('express');

const { Judge, loadRules } = require('./Judge');
const { State } = require('./State');
const { Db } = require('./Db');
const { Pangu } = require('./Pangu');
const { Guard } = require('./Guard');
const { Drill } = require('./Drill');

const PORT = Number(process.env.PORT || 8080);
const ROOT = path.join(__dirname, '..');

// ============================================================================
//  一、加载规则
// ============================================================================

const RULE_FILE = process.env.RULES_FILE || path.join(ROOT, 'config', 'rules.json');

function readRules() {
  try {
    return loadRules(RULE_FILE);
  } catch (e) {
    console.error('[Rules] 读取失败：', e.message);
    // 最后的兜底：一份最小可用规则，保证服务起得来
    return {
      sms_keywords: [
        { word: '安全账户', weight: 40, tag: 'S4' },
        { word: '别告诉家里人', weight: 45, tag: 'S3' },
      ],
      sms_negative: [],
      stages: { S0: { name: '无风险', need: 0 }, S1: { name: '套近乎', need: 15 }, S2: { name: '吓唬', need: 40 }, S3: { name: '隔离', need: 70 }, S4: { name: '要钱', need: 90 } },
      levels: { watch: 25, warn: 45, high: 70, block: 90 },
      combos: [],
      silence: {},
    };
  }
}

let currentRules = readRules();
let judge = new Judge(currentRules);
console.log('[Rules] 已加载，词条', currentRules.sms_keywords.length, '组合判据', (currentRules.combos || []).length);

// ============================================================================
//  二、连接依赖（失败即降级，不阻塞启动）
// ============================================================================

let redis = null;
async function initRedis() {
  const url = process.env.REDIS_URL;   // 如 redis://user:pass@host:6379/0
  if (!url) {
    console.warn('[Redis] 未配置 REDIS_URL，使用进程内存');
    return null;
  }
  try {
    const Redis = require('ioredis');
    const r = new Redis(url, {
      maxRetriesPerRequest: 1,
      retryStrategy: (t) => (t > 3 ? null : 300),   // 连不上就别一直重试
      lazyConnect: false,
    });
    r.on('error', (e) => console.warn('[Redis]', e.message));
    await new Promise((res, rej) => {
      r.once('ready', res);
      r.once('error', rej);
      setTimeout(() => rej(new Error('连接超时')), 3000);
    });
    console.log('[Redis] 连接成功');
    return r;
  } catch (e) {
    console.warn('[Redis] 连接失败，降级内存：', e.message);
    return null;
  }
}

let pool = null;
function initPool() {
  const host = process.env.RDS_HOST;
  if (!host) {
    console.warn('[RDS] 未配置 RDS_HOST，使用内存存储');
    return null;
  }
  try {
    const mysql = require('mysql2/promise');
    const p = mysql.createPool({
      host,
      port: Number(process.env.RDS_PORT || 3306),
      user: process.env.RDS_USER || 'root',
      password: process.env.RDS_PASS || '',
      database: process.env.RDS_DB || 'yindun',
      waitForConnections: true,
      connectionLimit: 5,
      connectTimeout: 4000,
      charset: 'utf8mb4',
    });
    return p;
  } catch (e) {
    console.warn('[RDS] 创建连接池失败：', e.message);
    return null;
  }
}

// ============================================================================
//  三、组装
// ============================================================================

const pangu = new Pangu();
const state = new State(null);   // 等 redis 连上后再注入
const db = new Db(null);
const drill = new Drill({ pangu, db });   // AI 模拟演练（定期）

/** SSE 订阅表：elderId → Set(res) */
const streams = new Map();

const guard = new Guard({
  judge,
  state,
  db,
  pangu,
  onPush: (elderId, payload) => {
    const set = streams.get(elderId);
    if (!set) return;
    const line = `data: ${JSON.stringify(payload)}\n\n`;
    for (const res of set) {
      try { res.write(line); } catch (e) { set.delete(res); }
    }
  },
});

// ============================================================================
//  四、Web 服务
// ============================================================================

const app = express();
app.use(express.json({ limit: '12mb' }));   // 要收 base64 截图，默认 256kb 不够
app.use(express.static(path.join(ROOT, 'public'), { etag: false }));

// ---------- 健康检查（部署后第一个要打的接口）----------
app.get('/api/health', async (req, res) => {
  const [rulesVer, stats] = await Promise.all([
    db.latestRules().catch(() => null),
    db.stats('demo').catch(() => ({ events: 0, alerts: 0 })),
  ]);
  res.json({
    ok: true,
    time: new Date().toISOString(),
    rules: { version: (rulesVer && rulesVer.version) || currentRules.version || 'file', keywords: currentRules.sms_keywords.length },
    storage: { rds: db.online, redis: !!redis },
    model: { enabled: pangu.enabled, model: pangu.model },
    stats,
  });
});

// ---------- 事件上报：整个系统的唯一入口 ----------
/**
 * POST /api/event
 * body: { elderId, type, ...data }
 * 返回：{ level, ui, pushed, alert? }
 */
app.post('/api/event', async (req, res) => {
  const body = req.body || {};
  const elderId = body.elderId || 'demo';
  const type = body.type;
  if (!type) return res.status(400).json({ ok: false, msg: '缺少 type' });

  try {
    const r = await guard.handle(elderId, type, body);
    res.json({ ok: true, ...r });
  } catch (e) {
    console.error('[event]', e);
    res.status(500).json({ ok: false, msg: e.message });
  }
});

// ---------- 端侧已判定预警的上报 ----------
/**
 * 安卓原生侧判完级之后，把"结论"推上来，云端只做两件事：落库 + 转推子女端。
 *
 * 【为什么不复用 /api/event】
 *   手机上的 Judge 已经判过一次了。如果走 /api/event，云端会拿自己那份 rules
 *   再判一次 —— 两边结论不一致时就会出现"手机已经盖屏了、云端说没事"，
 *   子女端看到的和老人端经历的完全是两回事。所以这个口子只收结论，不重判。
 *
 * 【历史 bug】
 *   Notifier.java 第 221 行一直 POST 的是 /api/alert，但后端从来没有这个路由，
 *   手机端每次上报都是 404，又被 catch 静默吞掉 —— 表现就是
 *   "手机上明明弹了预警，电脑上的子女端一条也收不到"。
 */
app.post('/api/alert', async (req, res) => {
  const b = req.body || {};
  const elderId = b.elderId || 'demo';
  const LEVELS = ['watch', 'warn', 'high', 'block'];
  const level = LEVELS.indexOf(String(b.level)) >= 0 ? String(b.level) : 'watch';
  const title = String(b.title || '').slice(0, 120);
  const body = String(b.body || '').slice(0, 500);
  const why = String(b.why || '').slice(0, 500);
  const channel = b.channel === 'push+call' ? 'push+call' : 'push';

  if (!title && !body) {
    return res.status(400).json({ ok: false, msg: 'title 和 body 不能都为空' });
  }

  try {
    const id = await db.addAlert({ elderId, level, title, body, why, channel });
    const d = new Date();
    const hhmm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');

    // 转推给所有开着子女端页面的浏览器（SSE）
    pushTo(elderId, {
      type: 'alert', alertId: id, level, title, body,
      summary: null, why, channel, time: hhmm,
    });

    // ---------- 阻断/高危：补一句 AI 人话 ----------
    // 【为什么这条路径原来没有摘要】
    // /api/alert 收到的是手机算完的结论（App 本地判定，断网也能用），
    // 云端只落库转推、不重新判级，所以走不到 Guard 里那段摘要逻辑，
    // 子女端看到的阻断卡摘要永远是空的。
    //
    // 【铁律照旧】绝不 await：卡片必须立刻推出去，摘要晚 10 秒到没关系。
    // 先落库再推，刷新才不丢。
    if ((level === 'block' || level === 'high') && pangu.enabled) {
      const lines = [`手机上报：${title}`, `细节：${body}`];
      if (why) lines.push(`判据：${why}`);
      pangu.explain(lines, '妈妈').then(async (s) => {
        if (!s) return;
        await db.setAlertSummary(id, s);
        pushTo(elderId, { type: 'summary', level, alertId: id, summary: s, time: hhmm });
        console.log(`[alert] 摘要回填 #${id} ${s}`);
      }).catch((e) => console.warn('[alert] 摘要失败', e.message));
    }

    res.json({ ok: true, id, pushed: true });
  } catch (e) {
    console.error('[alert]', e);
    res.status(500).json({ ok: false, msg: e.message });
  }
});

// ---------- 子女端实时订阅 ----------
app.get('/api/stream', (req, res) => {
  const elderId = req.query.elderId || 'demo';
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no');   // 关掉 nginx 缓冲
  res.flushHeaders();

  if (!streams.has(elderId)) streams.set(elderId, new Set());
  streams.get(elderId).add(res);

  // 心跳，防止中间层掐断空闲连接
  const hb = setInterval(() => {
    try { res.write(': ping\n\n'); } catch (e) { /* 忽略 */ }
  }, 15000);

  req.on('close', () => {
    clearInterval(hb);
    const set = streams.get(elderId);
    if (set) set.delete(res);
  });
});

// ---------- 子女端历史预警 ----------
app.get('/api/alerts', async (req, res) => {
  const elderId = req.query.elderId || 'demo';
  const limit = Number(req.query.limit || 20);
  res.json({ ok: true, list: await db.listAlerts(elderId, limit) });
});

// ---------- 熟人标记 ----------
app.post('/api/known', async (req, res) => {
  const elderId = (req.body && req.body.elderId) || 'demo';
  const k = await guard.markKnown(elderId, 15);
  res.json({ ok: true, known: k, hint: '只免打扰 15 分钟。子女照收提醒，动钱照样阻断。' });
});

// ---------- 重置一场演示 ----------
app.post('/api/reset', async (req, res) => {
  const elderId = (req.body && req.body.elderId) || 'demo';
  await guard.reset(elderId);
  res.json({ ok: true });
});

// ---------- 规则热更新（A 线改完直接生效）----------
app.post('/api/rules', async (req, res) => {
  const body = req.body || {};
  const next = body.rules || body;
  try {
    const j = new Judge(next);            // 这里会跑禁止字段校验，不合法直接抛
    const version = String(body.version || Date.now());
    await db.saveRules(version, next, body.note || '');
    judge = j;
    guard.judge = j;
    currentRules = next;
    res.json({ ok: true, version, keywords: next.sms_keywords.length });
  } catch (e) {
    res.status(400).json({ ok: false, msg: e.message });
  }
});

// ---------- 模型自检 ----------
app.get('/api/pangu/ping', async (req, res) => {
  res.json(await pangu.ping());
});

// ---------- 在页面里填 Key（演示用，生产别这么干）----------
app.post('/api/config', (req, res) => {
  const b = req.body || {};
  if (b.maasKey) pangu.key = b.maasKey;
  if (b.maasUrl) pangu.url = b.maasUrl;
  if (b.maasModel) pangu.model = b.maasModel;
  res.json({ ok: true, enabled: pangu.enabled, model: pangu.model });
});

// ============================================================================
//  一键上传可疑短信：上传 → 识别 → 给结论
//  POST /api/report  { image?: "data:image/png;base64,...", text?, number?, elderId? }
//  返回：{ stage, stageName, isFraud, type, reason, advice, signals, local, reportCount, blacklisted, cost }
// ============================================================================

/** 从 data URL 里拆出 mime 和 base64；格式不对返回 null */
function parseDataUrl(s) {
  const m = String(s || '').match(/^data:(image\/[a-zA-Z0-9.+-]+);base64,([\s\S]+)$/);
  if (!m) return null;
  return { mime: m[1], b64: m[2] };
}

app.post('/api/report', async (req, res) => {
  const b = req.body || {};
  const elderId = b.elderId || 'demo';
  // 谁提交的。老人端（App 原生页）传 elder，子女端（网页 role=child）传 child。
  // 白名单校验，不认识的值一律落成 elder，避免脏数据污染统计。
  const reporter = b.reporter === 'child' ? 'child' : 'elder';
  const number = String(b.number || '').trim().slice(0, 32);
  const text = String(b.text || '').trim().slice(0, 1200);

  const img = b.image ? parseDataUrl(b.image) : null;
  if (b.image && !img) {
    return res.status(400).json({ ok: false, msg: '图片格式不对，请传 data:image/...;base64,...' });
  }
  if (!img && !text) {
    return res.status(400).json({ ok: false, msg: '图片和文字至少要有一个' });
  }
  const imageBytes = img ? Math.floor(img.b64.length * 0.75) : 0;
  if (imageBytes > 6 * 1024 * 1024) {
    return res.status(413).json({ ok: false, msg: '图片太大（超过 6MB），请压缩后再传' });
  }

  // ---------- 第一层：本地规则先算一遍（不联网、毫秒级）----------
  // 有文字才算得出来；只有图片时本地无能为力，这正是模型该上场的地方。
  // who 传空：上传的短信号码归属未知，不能默认按"陌生人"加 15 分，否则正常快递短信也会被抬高。
  const local = text ? judge.scoreSms(text, '', false) : null;

  // ---------- 第二层：模型复核 ----------
  const t0 = Date.now();
  const ai = await pangu.analyzeSms({
    text,
    imageBase64: img ? img.b64 : null,
    mime: img ? img.mime : undefined,
  });
  const ms = Date.now() - t0;
  const cost = pangu.lastCost || 0;

  // 模型挂了就用本地结论兜底 —— 服务不能因为模型挂了就不能用
  if (!ai && !local) {
    return res.status(503).json({ ok: false, msg: '模型不可用，且没有可判定的文字。请检查 MAAS_KEY，或改用粘贴文字的方式。' });
  }

  const stage = ai ? ai.stage : ({ S0: 0, S1: 1, S2: 2, S3: 3, S4: 4 }[local.stage] || 0);
  const isFraud = ai ? ai.isFraud : local.level !== 'none';
  const result = {
    stage,
    stageName: ai ? ai.stageName : (local.stageName || '无风险'),
    isFraud,
    type: ai ? ai.type : (local.why || '规则命中'),
    reason: ai ? ai.reason : local.why,
    advice: ai ? ai.advice : (isFraud ? '先别转账，打电话确认一下' : '看起来没问题，正常处理即可'),
    signals: ai ? ai.signals : (local.hits || []).slice(0, 4).map(h => h.word),
  };

  // ---------- 落库 + 号码信誉 ----------
  try {
    await db.addReport({
      elderId, reporter, number,
      source: img ? 'image' : 'text',
      excerpt: text || '(图片)',
      imageBytes,
      isFraud, stage: result.stage, type: result.type,
      reason: result.reason, advice: result.advice,
      signals: result.signals,
      localScore: local ? local.score : 0,
      cost,
    });
  } catch (e) { console.warn('[report] 落库失败：', e.message); }

  const reportCount = number ? await db.countReports(number).catch(() => 0) : 0;
  const blacklisted = reportCount >= 3;   // 同一号码 ≥3 人举报即标红

  // ---------- 高危自动生成一条子女端预警 ----------
  let alert = null;
  if (result.isFraud && result.stage >= 3) {
    alert = {
      elderId,
      level: result.stage >= 4 ? 'block' : 'high',
      title: `有人给妈妈发了一条${result.type || '诈骗'}短信`,
      body: result.advice,
      // 身份写进 why：子女端时间线不读额外字段，直接显示这段文案，
      // 子女看到「我帮爸爸查的那条」和「爸爸自己查的」能分得清
      why: `${reporter === 'child' ? '子女代传' : '老人上传'} · S${result.stage}${result.stageName} · ${result.reason}`,
      channel: 'upload',
    };
    try {
      alert.id = await db.addAlert(alert);
      /* 【2026-11-09 修】原来这里手写了一条 {type:'alert', alert:{...}} 的嵌套消息，
         而通话链路用的是扁平结构。前端 renderAlert() 按扁平解析，拿到的
         level/title 全是 undefined —— 结果是「短信上报推过去了，子女端却渲染成一张空卡」。
         现在统一走 pushTo，只有一种格式，不再分叉。 */
      const d0 = new Date();
      const hhmm0 = String(d0.getHours()).padStart(2, '0') + ':' + String(d0.getMinutes()).padStart(2, '0');
      pushTo(elderId, {
        type: 'alert',
        alertId: alert.id,
        level: alert.level,
        title: alert.title,
        body: alert.body,
        why: alert.why,
        channel: alert.channel,
        summary: null,
        time: hhmm0,
      });
    } catch (e) { console.warn('[report] 推预警失败：', e.message); }
  }

  res.json({
    ok: true,
    reporter,
    ...result,
    local: local ? { score: local.score, stage: local.stage, stageName: local.stageName, why: local.why, hits: local.hits } : null,
    reportCount,
    blacklisted,
    alert,
    meta: { ms, cost, tokens: pangu.lastUsage, model: pangu.model, imageBytes, source: img ? 'image' : 'text' },
  });
});

/** 查某个号码的举报次数（号码信誉库） */
app.get('/api/report/stats', async (req, res) => {
  const number = String(req.query.number || '').trim();
  if (!number) return res.status(400).json({ ok: false, msg: '缺少 number' });
  const count = await db.countReports(number).catch(() => 0);
  res.json({ ok: true, number, count, blacklisted: count >= 3 });
});

/** 最近的上报记录 */
app.get('/api/reports', async (req, res) => {
  const limit = Number(req.query.limit || 20);
  res.json({ ok: true, list: await db.listReports(limit) });
});

// ============================================================================
//  AI 模拟演练（定期演练，功能④）
//  开局 → 4 轮应对 → 成绩单；模型扮骗子，判分由剧本定死
// ============================================================================

app.get('/api/drill/scenarios', (req, res) => {
  res.json({ ok: true, list: drill.listScenarios() });
});

/** 热点库：最近高发什么，可以指定某一类来演 */
app.get('/api/drill/topics', (req, res) => {
  res.json({ ok: true, list: drill.listTopics(), bridges: require('./CaseBank').BRIDGES });
});

/** 案例库：上传过哪些诈骗信息，能拿来衍生剧本 */
app.get('/api/drill/cases', async (req, res) => {
  const limit = Number(req.query.limit || 20);
  res.json({ ok: true, list: await drill.listCases(limit) });
});

// ---------- 定期计划 ----------
app.get('/api/drill/plan', async (req, res) => {
  const stats = await db.drillStats('demo').catch(() => null);
  res.json({ ok: true, plan: drill.getPlan(), stats });
});

app.post('/api/drill/plan', (req, res) => {
  const b = req.body || {};
  res.json({ ok: true, plan: drill.setPlan(b), hint: '改完立刻生效，不用重启' });
});

// ---------- 开一局 ----------
//  body: { mode?: 'auto'|'hot'|'case'|'mix'|'classic', scenario?, hotId?, reportId?, bridge?, elderId? }
//    classic → 用写死的四套剧本
//    hot     → 从热点库抽一类，现编台词
//    case    → 用你上传过的某条诈骗信息做种子
//    mix     → 热点 + 案例跨套路混合（先 A 建信任，再 B 收割）
//    auto    → 有案例就有一半概率用案例，否则用热点
app.post('/api/drill/start', async (req, res) => {
  const b = req.body || {};
  const elderId = b.elderId || 'demo';
  const mode = String(b.mode || '').trim();

  let key = b.scenario;
  if (!key && mode === 'classic') {
    const all = drill.listScenarios();
    key = all[Math.floor(Math.random() * all.length)].key;
  }
  // 不指定场景、也没指定模式 → 交给 Drill 自动挑（热点加权 / 案例优先）
  const opts = {
    mode: mode || 'auto',
    hotId: b.hotId || null,
    reportId: b.reportId || null,
    bridge: b.bridge || null,
    avoid: Array.isArray(b.avoid) ? b.avoid : [],
  };

  try {
    const view = await drill.start(elderId, key, opts);
    res.json({ ok: true, ...view });
  } catch (e) {
    res.status(400).json({ ok: false, msg: e.message });
  }
});

// ---------- 作答 ----------
app.post('/api/drill/turn', async (req, res) => {
  const { drillId, choice } = req.body || {};
  try {
    const r = await drill.turn(drillId, Number(choice));
    res.json(r);
  } catch (e) {
    res.status(400).json({ ok: false, msg: e.message });
  }
});

// ---------- 历史与薄弱环节 ----------
app.get('/api/drill/stats', async (req, res) => {
  const elderId = req.query.elderId || 'demo';
  const [stats, list] = await Promise.all([
    db.drillStats(elderId).catch(() => null),
    db.listDrills(elderId, 10).catch(() => []),
  ]);
  res.json({ ok: true, stats, list, plan: drill.getPlan() });
});

// ---------- 兜底：其余路径都给演示页 ----------
app.get(/^\/(?!api\/).*/, (req, res) => {
  res.sendFile(path.join(ROOT, 'public', 'index.html'));
});

// ============================================================================
//  五、启动
// ============================================================================

(async () => {
  const r = await initRedis();
  if (r) { redis = r; state.redis = r; }

  const p = initPool();
  if (p) { pool = p; db.pool = p; await db.init(); }

  // 数据库里有更新版本的规则就用库里的（A 线线上改的优先于文件）
  try {
    const latest = await db.latestRules();
    if (latest && latest.rules) {
      judge = new Judge(latest.rules);
      guard.judge = judge;
      currentRules = latest.rules;
      console.log('[Rules] 使用数据库版本', latest.version);
    }
  } catch (e) { /* 忽略 */ }

  app.listen(PORT, '0.0.0.0', () => {
    console.log('');
    console.log('  银盾 · 云端服务已启动');
    console.log('  ------------------------------------------');
    console.log(`  演示页面  http://localhost:${PORT}/`);
    console.log(`  健康检查  http://localhost:${PORT}/api/health`);
    console.log(`  存储      RDS ${db.online ? '已连接' : '内存降级'} / Redis ${redis ? '已连接' : '内存降级'}`);
    console.log(`  模型      ${pangu.enabled ? pangu.model : '未配置 MAAS_KEY'}`);
    const np = drill.getPlan().nextAt;
    console.log(`  定期演练  ${np ? np.toLocaleString('zh-CN') : '未开启'}`);
    console.log('  ------------------------------------------');
    console.log('');
  });
})();

// ============================================================================
//  六、定期演练调度
// ----------------------------------------------------------------------------
//  每分钟看一次表：到了约定的时间，就给老人端推一条"该演练了"，
//  同时给子女端记一条提醒 —— 演练是"疫苗"，不打就会忘。
// ============================================================================

function pushTo(elderId, payload) {
  const set = streams.get(elderId);
  if (!set) return;
  const line = `data: ${JSON.stringify(payload)}\n\n`;
  for (const r of set) { try { r.write(line); } catch (e) { set.delete(r); } }
}

setInterval(async () => {
  try {
    if (!drill.isDue()) return;
    drill.markFired();
    const plan = drill.getPlan();

    pushTo('demo', {
      type: 'drill.due',
      title: '该做反诈演练了',
      body: '3 分钟，练一练怎么对付骗子。练过的老人，被骗的概率低很多。',
      nextAt: plan.nextAt,
    });

    const alert = {
      elderId: 'demo',
      level: 'watch',
      title: '该提醒妈妈做反诈演练了',
      body: '每周一次，3 分钟。她一个人练，练完您能看到分数。',
      why: '定期演练 · 到点提醒',
      channel: 'drill',
    };
    alert.id = await db.addAlert(alert);
    /* 【2026-11-09 修】这里原来也是 {type:'alert', alert:{...}} 的嵌套格式，
       前端按扁平解析，渲染出来是一张「undefined」的空卡。
       全站只保留一种推送格式，别再分叉。 */
    const dnow = new Date();
    const hhmm2 = String(dnow.getHours()).padStart(2, '0') + ':' + String(dnow.getMinutes()).padStart(2, '0');
    pushTo('demo', {
      type: 'alert',
      alertId: alert.id,
      level: alert.level,
      title: alert.title,
      body: alert.body,
      why: alert.why,
      channel: alert.channel,
      summary: null,
      time: hhmm2,
    });
    console.log('[Drill] 定期演练已触发提醒');
  } catch (e) {
    console.warn('[Drill] 调度失败：', e.message);
  }
}, 60 * 1000);

// 进程级兜底，任何一个未捕获异常都不许把服务带走
process.on('uncaughtException', (e) => console.error('[未捕获异常]', e));
process.on('unhandledRejection', (e) => console.error('[未处理 Promise]', e));
