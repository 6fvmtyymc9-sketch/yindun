'use strict';
/**
 * ============================================================================
 *  Pairs.js —— 老人端身份 + 子女端配对
 * ----------------------------------------------------------------------------
 *  原来所有设备都用 elderId=demo：任何人订阅 /api/stream?elderId=demo
 *  都能看到这位老人的全部预警（含短信原文）。现在：
 *
 *  · 老人端：每台手机生成自己的 elderId + 密钥，请求头带
 *      X-Yd-Elder: <elderId>   X-Yd-Key: <密钥>
 *    第一次出现的 elderId 自动登记（只存密钥的哈希），之后密钥对不上一律 401。
 *  · 配对：老人端 POST /api/pair/start 拿 6 位配对码（10 分钟有效、一次性），
 *    子女端 POST /api/pair/claim { code } 换到只读凭证，请求头带 X-Yd-Child: <凭证>。
 *  · elderId=demo 保留为公开演示编号（模拟器默认用它），不需要任何凭证。
 *
 *  数据存在 cloud/data/pairs.json（已在 .gitignore 里），重启不丢。
 * ============================================================================
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const FILE = process.env.PAIRS_FILE || path.join(__dirname, '..', 'data', 'pairs.json');
const CODE_TTL_MS = 10 * 60 * 1000;
const DEMO = 'demo';

const sha = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');

class Pairs {
  constructor() {
    this.elders = {};     // elderId → { keyHash, createdAt }
    this.children = {};   // tokenHash → { elderId, createdAt }
    this.codes = new Map(); // code → { elderId, exp }
    this.claimFails = new Map(); // ip → { n, until }
    this.load();
  }

  load() {
    try {
      const d = JSON.parse(fs.readFileSync(FILE, 'utf8'));
      this.elders = d.elders || {};
      this.children = d.children || {};
    } catch (e) { /* 第一次运行没有文件 */ }
  }

  save() {
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      fs.writeFileSync(FILE, JSON.stringify({ elders: this.elders, children: this.children }, null, 2));
    } catch (e) {
      console.warn('[Pairs] 保存失败：', e.message);
    }
  }

  /** 老人端身份校验。新 elderId 自动登记；返回 true/false */
  checkElder(elderId, key) {
    if (!/^[a-z0-9]{8,32}$/.test(String(elderId || '')) || String(key || '').length < 16) return false;
    const rec = this.elders[elderId];
    if (!rec) {
      this.elders[elderId] = { keyHash: sha(key), createdAt: Date.now() };
      this.save();
      console.log('[Pairs] 新老人端登记：', elderId);
      return true;
    }
    return rec.keyHash === sha(key);
  }

  /** 子女端凭证 → elderId，无效返回 null */
  childElder(token) {
    if (!token) return null;
    const rec = this.children[sha(token)];
    return rec ? rec.elderId : null;
  }

  /** 生成一个 6 位配对码（同一老人只保留最新一个） */
  startCode(elderId) {
    for (const [c, v] of this.codes) if (v.elderId === elderId || v.exp < Date.now()) this.codes.delete(c);
    let code;
    do { code = String(crypto.randomInt(0, 1000000)).padStart(6, '0'); } while (this.codes.has(code));
    this.codes.set(code, { elderId, exp: Date.now() + CODE_TTL_MS });
    return { code, expiresIn: CODE_TTL_MS / 1000 };
  }

  /** 子女端用配对码换凭证。同一 IP 连错 5 次锁 10 分钟，防止暴力猜码 */
  claim(code, ip) {
    const f = this.claimFails.get(ip);
    if (f && f.until > Date.now()) return { ok: false, status: 429, msg: '尝试次数过多，请 10 分钟后再试' };
    const rec = this.codes.get(String(code || '').trim());
    if (!rec || rec.exp < Date.now()) {
      const n = (f ? f.n : 0) + 1;
      // 连错 5 次：锁 10 分钟，计数清零（锁过之后重新算）
      this.claimFails.set(ip, n >= 5 ? { n: 0, until: Date.now() + CODE_TTL_MS } : { n, until: 0 });
      return { ok: false, status: 400, msg: '配对码无效或已过期，请在老人手机上重新获取' };
    }
    this.codes.delete(String(code).trim());
    this.claimFails.delete(ip);
    const token = crypto.randomBytes(24).toString('hex');
    this.children[sha(token)] = { elderId: rec.elderId, createdAt: Date.now() };
    this.save();
    console.log('[Pairs] 子女端完成配对：', rec.elderId);
    return { ok: true, elderId: rec.elderId, token };
  }

  childCount(elderId) {
    return Object.values(this.children).filter(c => c.elderId === elderId).length;
  }
}

/**
 * 挂到 express 上：
 *   identify —— 最先执行：认出调用方是谁（老人端 / 子女端 / 未认证），挂到 req.yd
 *   guard    —— body 解析之后执行：请求里写的 elderId 必须和身份一致（demo 除外）
 */
function registerPairs(app, express) {
  const pairs = new Pairs();
  const ADMIN = process.env.YD_ADMIN || '';

  app.use((req, res, next) => {
    req.yd = { role: 'anon', elderId: null };
    const eid = req.get('X-Yd-Elder');
    const ctok = req.get('X-Yd-Child');
    if (eid) {
      if (!pairs.checkElder(eid, req.get('X-Yd-Key'))) return res.status(401).json({ ok: false, msg: '老人端身份校验失败' });
      req.yd = { role: 'elder', elderId: eid };
    } else if (ctok) {
      const e = pairs.childElder(ctok);
      if (!e) return res.status(401).json({ ok: false, msg: '配对已失效，请重新配对' });
      req.yd = { role: 'child', elderId: e };
    }
    // 子女端只能看，不能替老人上报事件（否则拿到凭证就能给自己推假警报）
    if (req.yd.role === 'child' && req.method === 'POST' && /^\/api\/(event|alert|known|callaudio)$/.test(req.path)) {
      return res.status(403).json({ ok: false, msg: '子女端不能上报老人端事件' });
    }
    // 管理接口：改大模型地址、改规则、清数据 —— 只允许本机，或带管理员口令
    if (/^\/api\/(config|rules|reset)$/.test(req.path) && req.method === 'POST') {
      const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
      const okAdmin = ADMIN ? req.get('X-Yd-Admin') === ADMIN : local;
      if (!okAdmin) return res.status(403).json({ ok: false, msg: '管理接口仅限本机或管理员调用' });
    }
    // 认证过的请求：查询参数里的 elderId 统一换成真实身份
    if (req.yd.elderId) req.query.elderId = req.yd.elderId;
    next();
  });

  // ---------- 配对接口 ----------
  const json = express.json({ limit: '16kb' });
  app.post('/api/pair/start', (req, res) => {
    if (req.yd.role !== 'elder') return res.status(401).json({ ok: false, msg: '只有老人端可以生成配对码' });
    res.json({ ok: true, ...pairs.startCode(req.yd.elderId) });
  });
  app.post('/api/pair/claim', json, (req, res) => {
    const r = pairs.claim((req.body || {}).code, req.ip);
    if (!r.ok) return res.status(r.status).json({ ok: false, msg: r.msg });
    res.json(r);
  });
  app.get('/api/pair/status', (req, res) => {
    if (req.yd.role !== 'elder') return res.status(401).json({ ok: false });
    res.json({ ok: true, elderId: req.yd.elderId, children: pairs.childCount(req.yd.elderId) });
  });

  return {
    pairs,
    /** body 解析之后挂：body 里的 elderId 不能冒充别人 */
    guard(req, res, next) {
      const want = (req.body && req.body.elderId) || req.query.elderId;
      if (req.yd.elderId) {
        if (req.body && typeof req.body === 'object') req.body.elderId = req.yd.elderId;
        return next();
      }
      if (want && want !== DEMO) return res.status(401).json({ ok: false, msg: '需要老人端身份或子女端配对凭证' });
      next();
    },
  };
}

module.exports = { registerPairs, Pairs };
