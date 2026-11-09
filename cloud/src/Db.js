'use strict';
/**
 * ============================================================================
 *  Db.js —— RDS(MySQL) 访问层
 * ----------------------------------------------------------------------------
 *  三张表，各管一件事：
 *    yd_event   事件流水（谁、什么时候、发生了什么、判了几级）
 *    yd_alert   预警记录（推给子女的每一条卡）
 *    yd_rules   规则版本（A 线改的 rules.json，改完入库，可回滚）
 *
 *  连不上数据库时全部降级为内存，保证演示不中断。
 * ============================================================================
 */

const mem = { event: [], alert: [], rules: [], report: [], drill: [] };
// 每张表独立自增，否则 id 会跳号（预警吃掉一个号，事件就缺一个）
const seqOf = { event: 1, alert: 1, rules: 1, report: 1, drill: 1 };

class Db {
  constructor(pool) {
    this.pool = pool; // mysql2/promise 的 Pool，或 null
    this.online = !!pool;
  }

  async init() {
    if (!this.pool) {
      console.warn('[Db] 未配置数据库，使用内存存���（重启即丢）');
      return;
    }
    try {
      const conn = await this.pool.getConnection();
      await conn.ping();
      conn.release();
      this.online = true;
      console.log('[Db] RDS 连接成功');
    } catch (e) {
      this.online = false;
      console.warn('[Db] RDS 连接失败，降级内存：', e.message);
    }
  }

  // ---------- 事件流水 ----------
  async addEvent(e) {
    const row = {
      elder_id: e.elderId || 'demo',
      type: e.type || '',
      code: e.code || '',
      level: e.level || 'none',
      score: e.score || 0,
      why: e.why || '',
      payload: JSON.stringify(e.payload || {}),
      created_at: new Date(),
    };
    if (this.online) {
      try {
        const [r] = await this.pool.execute(
          `INSERT INTO yd_event (elder_id,type,code,level,score,why,payload,created_at)
           VALUES (?,?,?,?,?,?,?,?)`,
          [row.elder_id, row.type, row.code, row.level, row.score, row.why, row.payload, row.created_at]
        );
        return r.insertId;
      } catch (e2) {
        console.warn('[Db] addEvent 失败：', e2.message);
      }
    }
    row.id = seqOf.event++;
    mem.event.push(row);
    return row.id;
  }

  // ---------- 预警记录 ----------
  async addAlert(a) {
    const row = {
      elder_id: a.elderId || 'demo',
      level: a.level || 'watch',
      title: a.title || '',
      body: a.body || '',
      why: a.why || '',
      summary: a.summary || null,
      channel: a.channel || 'push',
      created_at: new Date(),
    };
    if (this.online) {
      try {
        const [r] = await this.pool.execute(
          `INSERT INTO yd_alert (elder_id,level,title,body,why,summary,channel,created_at)
           VALUES (?,?,?,?,?,?,?,?)`,
          [row.elder_id, row.level, row.title, row.body, row.why, row.summary, row.channel, row.created_at]
        );
        return r.insertId;
      } catch (e2) {
        console.warn('[Db] addAlert 失败：', e2.message);
      }
    }
    row.id = seqOf.alert++;
    mem.alert.push(row);
    return row.id;
  }

  /**
   * 补写 AI 摘要。
   * 为什么是"补写"而不是"插入时带上"：
   *   摘要要等模型（可能 5 秒），而阻断卡必须立刻推给子女。
   *   所以卡片先落库、先推送，摘要生成后再回填这一列。
   * 之前的 bug：摘要只走 SSE 推给前端，没写库 —— 子女端刷新一下，AI 说的话就没了。
   */
  async setAlertSummary(alertId, summary) {
    if (!alertId || !summary) return false;
    const s = String(summary).slice(0, 2000);
    if (this.online) {
      try {
        await this.pool.execute(
          `UPDATE yd_alert SET summary=? WHERE id=?`,
          [s, alertId]
        );
        return true;
      } catch (e) {
        console.warn('[Db] setAlertSummary 失败：', e.message);
      }
    }
    const hit = mem.alert.find(a => a.id === alertId);
    if (hit) { hit.summary = s; return true; }
    return false;
  }

  /** 子女端拉取时间线（倒序），演示页面用这个接口 */
  async listAlerts(elderId, limit = 20) {
    if (this.online) {
      try {
        const [rows] = await this.pool.execute(
          // channel 必须一起查：子女端靠它分「通话 / 短信」两个视图，
          // 不返回的话历史回填的卡片全部落到默认视图，刷新一下就串栏
          `SELECT id,level,title,body,why,summary,channel,created_at
             FROM yd_alert WHERE elder_id=? ORDER BY id DESC LIMIT ?`,
          [elderId, Number(limit) || 20]
        );
        return rows;
      } catch (e) {
        console.warn('[Db] listAlerts 失败：', e.message);
      }
    }
    return mem.alert
      .filter(a => a.elder_id === elderId)
      .slice(-limit)
      .reverse();
  }

  // ---------- 规则版本 ----------
  async saveRules(version, rulesObj, note) {
    const json = typeof rulesObj === 'string' ? rulesObj : JSON.stringify(rulesObj);
    if (this.online) {
      try {
        await this.pool.execute(
          `INSERT INTO yd_rules (version,rules,note,created_at) VALUES (?,?,?,?)`,
          [version, json, note || '', new Date()]
        );
        return true;
      } catch (e) {
        console.warn('[Db] saveRules 失败：', e.message);
      }
    }
    mem.rules.push({ version, rules: json, note, created_at: new Date() });
    return true;
  }

  /** 取最新一版规则。表为空时返回 null，调用方回退到文件里那份 */
  async latestRules() {
    if (this.online) {
      try {
        const [rows] = await this.pool.execute(
          `SELECT version,rules,created_at FROM yd_rules ORDER BY id DESC LIMIT 1`
        );
        if (rows.length) {
          return { version: rows[0].version, rules: JSON.parse(rows[0].rules) };
        }
        return null;
      } catch (e) {
        console.warn('[Db] latestRules 失败：', e.message);
      }
    }
    if (!mem.rules.length) return null;
    const last = mem.rules[mem.rules.length - 1];
    return { version: last.version, rules: JSON.parse(last.rules) };
  }

  // ---------- 诈骗信息上报（一键上传）----------
  /**
   * 存一条上报记录。图片只存"有没有图 + 字节数"，不存原图 ——
   * 老人上传的截图可能含姓名、卡号，能不落库就不落库。
   */
  async addReport(r) {
    const row = {
      elder_id: r.elderId || 'demo',
      // 谁提交的：老人自己在手机上查，还是子女在微信里看到后转过来。
      // 只认两个值，别的一律当老人自己提交 —— 宁可归错也不写脏数据。
      reporter: r.reporter === 'child' ? 'child' : 'elder',
      number: r.number || '',
      source: r.source || 'text',        // text | image
      excerpt: (r.excerpt || '').slice(0, 200),
      image_bytes: r.imageBytes || 0,
      is_fraud: r.isFraud ? 1 : 0,
      stage: Number(r.stage || 0),
      type: r.type || '',
      reason: r.reason || '',
      advice: r.advice || '',
      signals: JSON.stringify(r.signals || []),
      local_score: Number(r.localScore || 0),
      cost: Number(r.cost || 0),
      created_at: new Date(),
    };
    if (this.online) {
      try {
        const [res] = await this.pool.execute(
          `INSERT INTO yd_report (elder_id,reporter,number,source,excerpt,image_bytes,is_fraud,stage,type,reason,advice,signals,local_score,cost,created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
          [row.elder_id, row.reporter, row.number, row.source, row.excerpt, row.image_bytes, row.is_fraud,
           row.stage, row.type, row.reason, row.advice, row.signals, row.local_score, row.cost, row.created_at]
        );
        return res.insertId;
      } catch (e) {
        console.warn('[Db] addReport 失败：', e.message);
      }
    }
    row.id = seqOf.report++;
    mem.report.push(row);
    return row.id;
  }

  /** 某个号码被多少人举报过（号码信誉库，≥3 即标红） */
  async countReports(number) {
    if (!number) return 0;
    if (this.online) {
      try {
        const [[r]] = await this.pool.execute(
          `SELECT COUNT(*) AS n FROM yd_report WHERE number=? AND is_fraud=1`, [number]);
        return Number(r.n) || 0;
      } catch (e) { /* 落下去走内存 */ }
    }
    return mem.report.filter(x => x.number === number && x.is_fraud).length;
  }

  /** 最近的上报记录，给"查看历史"用 */
  async listReports(limit = 20) {
    if (this.online) {
      try {
        const [rows] = await this.pool.execute(
          `SELECT id,reporter,number,source,excerpt,is_fraud,stage,type,reason,created_at
             FROM yd_report ORDER BY id DESC LIMIT ?`, [Number(limit) || 20]);
        return rows;
      } catch (e) { /* 落下去走内存 */ }
    }
    return mem.report.slice(-limit).reverse();
  }

  // ---------- 演练记录（定期演练）----------
  async addDrill(r) {
    const row = {
      elder_id: r.elderId || 'demo',
      scenario: r.scenario || '',
      score: Number(r.score || 0),
      rounds: Number(r.rounds || 4),
      passed: r.passed ? 1 : 0,
      wrong_stages: JSON.stringify(r.wrongStages || []),
      cost: Number(r.cost || 0),
      created_at: new Date(),
    };
    if (this.online) {
      try {
        const [res] = await this.pool.execute(
          `INSERT INTO yd_drill (elder_id,scenario,score,rounds,passed,wrong_stages,cost,created_at)
           VALUES (?,?,?,?,?,?,?,?)`,
          [row.elder_id, row.scenario, row.score, row.rounds, row.passed, row.wrong_stages, row.cost, row.created_at]
        );
        return res.insertId;
      } catch (e) {
        console.warn('[Db] addDrill 失败：', e.message);
      }
    }
    row.id = seqOf.drill++;
    mem.drill.push(row);
    return row.id;
  }

  async listDrills(elderId, limit = 10) {
    if (this.online) {
      try {
        const [rows] = await this.pool.execute(
          `SELECT id,scenario,score,rounds,passed,wrong_stages,created_at
             FROM yd_drill WHERE elder_id=? ORDER BY id DESC LIMIT ?`,
          [elderId, Number(limit) || 10]);
        return rows;
      } catch (e) { /* 落下去走内存 */ }
    }
    return mem.drill.filter(x => x.elder_id === elderId).slice(-limit).reverse();
  }

  /**
   * 演练汇总：局数、平均分、最近几局趋势、最常失守的环节。
   * "最常失守"是给子女看的重点 —— 光说"你妈考了 60 分"没用，
   * 要说"她三次里有两次都被'别告诉家人'那句话套住了"。
   */
  async drillStats(elderId) {
    const rows = await this.listDrills(elderId, 20);
    const STAGE_NAME = { 1: '套近乎', 2: '吓唬', 3: '隔离', 4: '要钱' };
    const cnt = {};
    rows.forEach(r => {
      let arr = [];
      try { arr = JSON.parse(r.wrong_stages || '[]'); } catch (e) { arr = []; }
      arr.forEach(s => { cnt[s] = (cnt[s] || 0) + 1; });
    });
    const weak = Object.entries(cnt)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 2)
      .map(([stage, n]) => ({ stage: Number(stage), name: STAGE_NAME[stage] || '第' + stage + '步', times: n }));

    const scores = rows.map(r => r.score);
    return {
      count: rows.length,
      avg: scores.length ? Math.round(scores.reduce((a, b) => a + b, 0) / scores.length) : null,
      lastScore: scores[0] != null ? scores[0] : null,
      trend: rows.slice(0, 6).reverse().map(r => ({ score: r.score, at: r.created_at, scenario: r.scenario })),
      weak,
      lastAt: rows.length ? rows[0].created_at : null,
    };
  }

  /** 统计，答辩时用来展示"跑了多少条" */
  async stats(elderId) {
    if (this.online) {
      try {
        const [[ev]] = await this.pool.execute(
          `SELECT COUNT(*) AS n FROM yd_event WHERE elder_id=?`, [elderId]);
        const [[al]] = await this.pool.execute(
          `SELECT COUNT(*) AS n FROM yd_alert WHERE elder_id=?`, [elderId]);
        const [[rp]] = await this.pool.execute(
          `SELECT COUNT(*) AS n FROM yd_report WHERE elder_id=?`, [elderId]);
        return { events: ev.n, alerts: al.n, reports: rp.n };
      } catch (e) { /* 落下去走内存 */ }
    }
    return {
      events: mem.event.filter(x => x.elder_id === elderId).length,
      alerts: mem.alert.filter(x => x.elder_id === elderId).length,
      reports: mem.report.filter(x => x.elder_id === elderId).length,
    };
  }
}

module.exports = { Db };
