'use strict';
/**
 * 判定引擎回归测试：node test/run.js [--all] [holdout.json]
 *
 *   诈骗样本必须判到 warn 及以上（会提醒老人、推送子女）
 *   正常样本最多 watch（只记一笔，不打扰）
 *
 * 输出漏报率、误报率和每一条判错的样本，判错就回去改 config/rules.json。
 * 加 --all 打印全部样本的分数和级别。
 */
const path = require('path');
const { Judge, loadRules } = require('../src/Judge');

const RANK = { none: 0, watch: 1, warn: 2, high: 3, block: 4 };
const rules = loadRules(path.join(__dirname, '..', 'config', 'rules.json'));
const judge = new Judge(rules);
const file = process.argv.find(a => a.endsWith('.json')) || './cases.json';
const { cases } = require(require('path').resolve(__dirname, file.replace(/^test\//, '')));
const showAll = process.argv.includes('--all');

let fn = 0, fp = 0, nFraud = 0, nNormal = 0;
const byType = {};
const fails = [];

for (const c of cases) {
  const r = judge.scoreSms(c.text, c.who, !!c.code);
  const rank = RANK[r.level] || 0;
  const ok = c.expect === 'fraud' ? rank >= RANK.warn : rank <= RANK.watch;
  if (c.expect === 'fraud') { nFraud++; if (!ok) fn++; } else { nNormal++; if (!ok) fp++; }
  const t = (byType[c.type] = byType[c.type] || { n: 0, ok: 0 });
  t.n++; if (ok) t.ok++;
  const line = `${ok ? '  ' : '✗ '}${String(r.score).padStart(4)}  ${r.stage}  ${r.level.padEnd(5)}  [${c.type}/${c.who}] ${c.text.slice(0, 34)}`;
  if (!ok) fails.push(line + `\n        命中：${r.why}`);
  else if (showAll) console.log(line);
}

if (fails.length) {
  console.log('\n判错的样本：');
  for (const f of fails) console.log(f);
}

console.log('\n分类结果：');
for (const [k, v] of Object.entries(byType)) {
  console.log(`  ${k.padEnd(6, '　')} ${v.ok}/${v.n}`);
}
const pct = (a, b) => (b ? ((a / b) * 100).toFixed(1) : '0.0') + '%';
console.log(`\n诈骗样本 ${nFraud} 条，漏报 ${fn} 条（漏报率 ${pct(fn, nFraud)}）`);
console.log(`正常样本 ${nNormal} 条，误报 ${fp} 条（误报率 ${pct(fp, nNormal)}）`);
console.log(`规则版本 ${rules.version} · 关键词 ${rules.sms_keywords.length} · 反例 ${rules.sms_negative.length}`);
process.exitCode = fn + fp > 0 ? 1 : 0;
