'use strict';
/**
 * 规则引擎 v3.4 留出集逐条结果
 * ============================================================================
 * 和 compare-llm.js / out-tinybert.json 对齐同一份 holdout.json、同一个命中口径：
 *   诈骗样本：level 达到 warn 及以上才算抓到
 *   正常样本：level 停在 watch 及以下才算放过
 * 口径与 test/run.js 完全一致，保证三路数字可比。
 *
 * 用法：node test/compare-rules.js [holdout.json]
 */
const fs = require('fs');
const path = require('path');
const { Judge } = require('../src/Judge');

const SRC = process.argv[2] || 'holdout.json';
const OUT = path.join(__dirname, 'out-rules.json');
const RANK = { none: 0, watch: 1, warn: 2, high: 3, block: 4 };

const rules = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'config', 'rules.json'), 'utf8'));
const judge = new Judge(rules);
const set = JSON.parse(fs.readFileSync(path.join(__dirname, SRC), 'utf8')).cases;

const rows = set.map((c) => {
  const t0 = process.hrtime.bigint();
  const r = judge.scoreSms(c.text, c.who || '', c.who === 'service');
  const us = Number((process.hrtime.bigint() - t0) / 1000n) / 1000;
  const truth = c.expect === 'fraud';
  const caught = (RANK[r.level] || 0) >= RANK.warn;
  return {
    type: c.type, who: c.who, text: c.text, expect: c.expect,
    score: r.score, stage: r.stage, level: r.level,
    pred: caught ? 'fraud' : 'normal',
    hit: caught === truth,
    us,
    hits: (r.hits || []).map(h => h.word).slice(0, 4),
  };
});

fs.writeFileSync(OUT, JSON.stringify(rows, null, 1), 'utf8');

const fraud = rows.filter(r => r.expect === 'fraud');
const normal = rows.filter(r => r.expect !== 'fraud');
const miss = fraud.filter(r => r.pred !== 'fraud').length;
const falarm = normal.filter(r => r.pred === 'fraud').length;
const avgUs = rows.reduce((s, r) => s + r.us, 0) / rows.length;

console.log(`诈骗 ${fraud.length} 条，漏报 ${miss} 条（${(miss / fraud.length * 100).toFixed(1)}%）`);
console.log(`正常 ${normal.length} 条，误报 ${falarm} 条（${(falarm / normal.length * 100).toFixed(1)}%）`);
console.log(`平均耗时 ${avgUs.toFixed(3)} ms/条（离线，无网络）`);
console.log(`明细 → ${OUT}`);
