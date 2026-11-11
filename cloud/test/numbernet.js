'use strict';
/**
 * 号码网离线自测：不连 MySQL、不连 ECS，只验 Db 那三个方法是否按设计工作。
 *
 * 为什么需要这个文件：
 *   号码网的"升级红色提醒"要 shield ≥ 3，也就是三个不同设备独立判过同一个号。
 *   本地只有一个 App 实例，HTTP 接口造不出这种数据，所以直接测 Db 层。
 *   它测的是判断逻辑，不是 SQL —— MySQL 那一段要靠线上真库验，这点必须说清楚。
 *
 * 跑法：node test/numbernet.js
 */

process.env.MYSQL_HOST = '';          // 强制走内存
const { Db } = require('../src/Db');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}

(async () => {
  const db = new Db({ host: '', user: '', password: '', database: '' });
  await db.init().catch(() => {});
  const NUM = '13900001111';

  console.log('\n[1] 三台设备各判一次同一号码');
  const today = new Date(); today.setHours(0, 0, 0, 0);
  for (const elder of ['e1', 'e2', 'e3']) {
    await db.addReport({ elderId: elder, reporter: 'callaudio', number: NUM,
      source: 'call', isFraud: true, stage: 4, type: '冒充公检法', excerpt: '', signals: [] });
  }
  let s = await db.countReportsSplit(NUM);
  ok('shield = 3', s.shield === 3, JSON.stringify(s));
  ok('user = 0（没有混进人举报的）', s.user === 0, JSON.stringify(s));
  ok('total = 3', s.total === 3, JSON.stringify(s));

  console.log('\n[2] 同一台设备同一天再判一次，不该重复计');
  ok('e1 今天已记过', await db.hasReportSince(NUM, 'e1', today.getTime()));
  ok('e9 今天没记过', !(await db.hasReportSince(NUM, 'e9', today.getTime())));

  console.log('\n[3] 人举报的与人举报的相加，机器判定不冒充用户举报');
  await db.addReport({ elderId: 'e1', reporter: 'elder', number: NUM, isFraud: true });
  await db.addReport({ elderId: 'e1', reporter: 'child', number: NUM, isFraud: true });
  s = await db.countReportsSplit(NUM);
  ok('user = 2', s.user === 2, JSON.stringify(s));
  ok('shield 仍是 3', s.shield === 3, JSON.stringify(s));
  ok('total = 5', s.total === 5, JSON.stringify(s));

  console.log('\n[4] 脏数据的兜底：乱填 reporter / source 不写坏库');
  const r = await db.addReport({ reporter: 'hacker', number: '10086', source: 'weird', isFraud: true });
  const one = (await db.listReports(1))[0];
  ok('未知 reporter 归到 elder', one && one.reporter === 'elder', JSON.stringify(one && one.reporter));
  ok('未知 source 归到 text', one && one.source === 'text', JSON.stringify(one && one.source));
  ok('addReport 有返回 id', typeof r !== 'undefined');

  console.log('\n[5] 没被判过的号码返回全 0');
  const z = await db.countReportsSplit('13800000000');
  ok('全 0', z.user === 0 && z.shield === 0 && z.total === 0, JSON.stringify(z));

  console.log(`\n结果：${pass} 通过 / ${fail} 失败\n`);
  process.exit(fail ? 1 : 0);
})();
