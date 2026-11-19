#!/usr/bin/env node
'use strict';
/**
 * check_storage.js —— 存储链路核对（迁 RDS / DCS 前后各跑一次，输出可直接 diff）
 *
 * 用法：
 *   node tools/check_storage.js                       # 读 cloud/.env
 *   node tools/check_storage.js --env /opt/yindun/.env # 部署机上
 *   node tools/check_storage.js --expect alerts=1581,reports=1225
 *   node tools/check_storage.js --forbid-local         # 迁移后必须用：连到 localhost 直接判失败
 *
 * 退出码：0 = 全部通过；1 = 有硬问题（连不上 / 表缺列 / 期望值不符 / 连到了本机库）
 *
 * 为什么需要它：
 *   1. /api/health 里的 storage.rds 只代表"某个 MySQL 连上了"，
 *      连本机 127.0.0.1 也是 true —— 迁移后**不能靠这个字段判断是不是连到云 RDS**
 *   2. 迁移漏跑 sql/migrate_20261110_callaudio.sql 不报错，
 *      reporter 会被 MySQL 静默截断成空串（录音复盘的上报直接丢）
 *   3. 漏跑 sql/migrate_20261111_event.sql，yd_alert 缺 call_id/kind，事件回放失效
 *
 * 本脚本**只读**：不建表、不写数据、不删任何东西。
 */

const fs = require('fs');
const path = require('path');
const { createRequire } = require('module');

// 依赖解析：两套目录结构都要能用
//   仓库里    tools/check_storage.js   → ../cloud/package.json（node_modules 在 cloud/ 下）
//   部署机上  /opt/yindun/tools/xxx.js → ../package.json（node_modules 在 /opt/yindun 下）
function pickPkg() {
  const cands = [
    path.join(__dirname, '..', 'cloud', 'package.json'),
    path.join(__dirname, '..', 'package.json'),
  ];
  for (const p of cands) if (fs.existsSync(p)) return p;
  console.error(`✗ 找不到 package.json（试过 ${cands.join(' / ')}）—— 本脚本要和服务代码放在一起跑`);
  process.exit(1);
}
const req = createRequire(pickPkg());

// .env 默认位置同理：仓库用 cloud/.env，部署机用 /opt/yindun/.env
function defaultEnv() {
  const a = path.join(__dirname, '..', 'cloud', '.env');
  return fs.existsSync(a) ? a : path.join(__dirname, '..', '.env');
}

// ---------------- 参数 ----------------
function argOf(name, def) {
  const i = process.argv.indexOf(name);
  if (i < 0) return def;
  const v = process.argv[i + 1];
  return v && !v.startsWith('--') ? v : true;
}
const ENV_FILE = argOf('--env', defaultEnv());
const FORBID_LOCAL = process.argv.includes('--forbid-local');
const EXPECT_RAW = argOf('--expect', null);

// ---------------- 读 .env（与 cloud/src/env.js 同语义：同键取第一个）----------------
function loadEnv(file) {
  const out = {};
  if (!fs.existsSync(file)) {
    console.error(`✗ .env 不存在: ${file}`);
    process.exit(1);
  }
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (/^\s*#/.test(line) || !line.trim()) continue;
    const m = line.match(/^\s*([A-Za-z_0-9]+)\s*=\s*(.*?)\s*$/);
    if (m && out[m[1]] === undefined) out[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
  return out;
}

const env = loadEnv(ENV_FILE);
const mask = (u) => (u ? u.replace(/:\/\/([^@]*)@/, '://***@') : '');

const OK = '\x1b[32m✓\x1b[0m';
const NO = '\x1b[31m✗\x1b[0m';
const WARN = '\x1b[33m!\x1b[0m';
const problems = [];

function line(label, value) {
  console.log(`  ${label.padEnd(12)}${value}`);
}

(async () => {
  console.log('\n=== 银盾 · 存储链路核对 ===');
  line('.env', ENV_FILE);

  // ---------------- MySQL ----------------
  let my = null;
  const host = env.RDS_HOST;
  const port = Number(env.RDS_PORT || 3306);
  if (!host) {
    console.log(`\n${NO} MySQL：.env 里没有 RDS_HOST，服务会降级成进程内存`);
    problems.push('RDS_HOST 未配置');
  } else {
    const shownHost = `${host}:${port}/${env.RDS_DB || '?'}`;
    try {
      const mysql = req('mysql2/promise');
      my = await mysql.createConnection({
        host, port,
        user: env.RDS_USER || 'root',
        password: env.RDS_PASS || '',
        database: env.RDS_DB || undefined,
        connectTimeout: 6000,
      });
      const [[ver]] = await my.query('SELECT VERSION() AS v, DATABASE() AS db');
      console.log(`\n${OK} MySQL 已连接  ${ver.v}  @ ${shownHost}`);
      line('连接串', mask(`mysql://${env.RDS_USER}:***@${shownHost}`));

      const isLocal = ['127.0.0.1', 'localhost', '::1'].includes(String(host).toLowerCase());
      if (isLocal && FORBID_LOCAL) {
        console.log(`  ${NO} --forbid-local：连的是本机库（${host}），迁移未生效`);
        problems.push('迁移后仍连本机 MySQL');
      } else if (isLocal) {
        console.log(`  ${WARN} 注意：连的是本机库，当前仍不是云 RDS`);
      }

      // ---- 表与条数 ----
      const [tables] = await my.query('SHOW TABLES');
      const names = tables.map((r) => Object.values(r)[0]);
      const want = ['yd_alert', 'yd_report', 'yd_drill', 'yd_event'];
      console.log('\n表结构与条数');
      const counts = {};
      for (const t of want) {
        if (!names.includes(t)) {
          console.log(`  ${NO} ${t.padEnd(10)} 表不存在`);
          problems.push(`缺表 ${t}`);
          continue;
        }
        const [[c]] = await my.query(`SELECT COUNT(*) AS n FROM \`${t}\``);
        counts[t] = Number(c.n);
        const extra = [];
        if (t === 'yd_alert') {
          const [cols] = await my.query(
            `SELECT COLUMN_NAME FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yd_alert'`);
          const set = new Set(cols.map((r) => r.COLUMN_NAME));
          for (const c2 of ['call_id', 'kind']) {
            if (!set.has(c2)) { extra.push(`${c2} 缺失`); problems.push(`yd_alert 缺列 ${c2}（migrate_20261111_event.sql 没跑）`); }
          }
          extra.push(extra.length ? '' : 'call_id ✓ kind ✓');
        }
        if (t === 'yd_report') {
          const [[col]] = await my.query(
            `SELECT COLUMN_TYPE AS t FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yd_report' AND COLUMN_NAME='reporter'`);
          if (!col || !String(col.t).includes('callaudio')) {
            extra.push('reporter 缺 callaudio');
            problems.push('yd_report.reporter 不含 callaudio（migrate_20261110_callaudio.sql 没跑，录音复盘上报会被静默截断）');
          } else {
            extra.push('reporter ✓');
          }
        }
        console.log(`  ${OK} ${t.padEnd(10)} ${String(c.n).padStart(6)} 条   ${extra.join('  ')}`);
      }

      // ---- 期望值比对 ----
      if (typeof EXPECT_RAW === 'string' && EXPECT_RAW.includes('=')) {
        console.log('\n期望值比对');
        for (const kv of EXPECT_RAW.split(',')) {
          const [k, v] = kv.split('=').map((s) => s.trim());
          const table = k.startsWith('alert') ? 'yd_alert' : k.startsWith('report') ? 'yd_report'
            : k.startsWith('drill') ? 'yd_drill' : k.startsWith('event') ? 'yd_event' : null;
          if (!table) { console.log(`  ${WARN} 未知名 ${k}`); continue; }
          const got = counts[table];
          const same = String(got) === String(Number(v));
          console.log(`  ${same ? OK : NO} ${k.padEnd(8)} 实测 ${got}  期望 ${v}`);
          if (!same) problems.push(`${k} 实测 ${got} ≠ 期望 ${v}`);
        }
      }
    } catch (e) {
      console.log(`\n${NO} MySQL 连接失败  @ ${shownHost}`);
      console.log(`     ${e.message}`);
      problems.push(`MySQL 连不上：${e.message}`);
    } finally {
      if (my) await my.end().catch(() => {});
    }
  }

  // ---------------- Redis ----------------
  const url = env.REDIS_URL;
  if (!url) {
    console.log(`\n${NO} Redis：.env 里没有 REDIS_URL，服务会降级成进程内存`);
    problems.push('REDIS_URL 未配置');
  } else {
    let r = null;
    let lastErr = null;
    try {
      const Redis = req('ioredis');
      r = new Redis(url, { lazyConnect: true, maxRetriesPerRequest: 1, connectTimeout: 6000 });
      // 必须挂 error 监听：lazyConnect 下不挂会打 "[ioredis] Unhandled error event" 噪音，
      // 而且拿不到真实原因（只能看到笼统的 Connection is closed）
      r.on('error', (e) => { lastErr = e; });
      await r.connect();
      const info = await r.info('server');
      const v = (info.match(/redis_version:(\S+)/) || [, '?'])[1];
      const size = await r.dbsize();
      console.log(`\n${OK} Redis 已连接  ${v}  keys=${size}`);
      line('连接串', mask(url));
    } catch (e) {
      console.log(`\n${NO} Redis 连接失败`);
      console.log(`     ${(lastErr && lastErr.message) || e.message}`);
      problems.push(`Redis 连不上：${(lastErr && lastErr.message) || e.message}`);
    } finally {
      if (r) await r.quit().catch(() => {});
    }
  }

  // ---------------- 汇总 ----------------
  console.log('');
  if (problems.length === 0) {
    console.log(`${OK} 全部通过（本脚本只读，未写入任何数据）\n`);
    process.exit(0);
  }
  console.log(`${NO} 有 ${problems.length} 个问题：`);
  problems.forEach((p, i) => console.log(`   ${i + 1}. ${p}`));
  console.log('');
  process.exit(1);
})();
