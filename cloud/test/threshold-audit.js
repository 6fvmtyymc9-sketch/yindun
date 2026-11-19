#!/usr/bin/env node
'use strict';
/**
 * threshold-audit.js —— 用本地规则引擎复算评估集，核对"门槛"到底有几条线
 *
 * 为什么要这个脚本：
 *   评估报告里的「识别层 18.9%」「拦截层 50.0%」都是服务端口径，
 *   但它们对应的是 server.js 里三个**不同**的判断，很容易被叫错名字：
 *
 *     server.js:614   const isFraud = ai ? ai.isFraud : local.level !== 'none';
 *     server.js:658   if (result.isFraud && result.stage >= 3) { db.addAlert(...); pushTo(...); }
 *     server.js:702   wouldAlert: !!(result.isFraud && result.stage >= 3)
 *
 *   即：判为诈骗（isFraud）是一件事，把预警推给子女是另一件事，后者多一道 stage>=3。
 *   这个脚本把两条线分开算，并给出 who 参数（陌生号码 +15 分）的影响。
 *
 * 用法：
 *   node cloud/test/threshold-audit.js <labels.json>
 *   默认读 D:/华为ict/交付包/_inbox/同学乙_20261119/demo/eval-calls/labels.json
 *
 * 只读本地 rules.json，不联网、不写库、不花配额。
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const { Judge } = require(path.join(ROOT, 'cloud/src/Judge.js'));

const LABELS = process.argv[2]
  || 'D:/华为ict/交付包/_inbox/同学乙_20261119/demo/eval-calls/labels.json';

const rules = JSON.parse(fs.readFileSync(path.join(ROOT, 'cloud/config/rules.json'), 'utf8'));
const judge = new Judge(rules);
const rows = JSON.parse(fs.readFileSync(LABELS, 'utf8'));

console.log(`规则 v${rules.version} · ${(rules.sms_keywords || []).length} 词 · levels=${JSON.stringify(judge.levels)}`);
console.log(`样本 ${rows.length} 条：诈骗 ${rows.filter(r => r.label === 1).length} / 正常 ${rows.filter(r => r.label === 0).length}\n`);

const RANK = { S0: 0, S1: 1, S2: 2, S3: 3, S4: 4 };
const pct = (a, b) => (b ? ((a / b) * 100).toFixed(1) + '%' : '--');

/** 一条样本在给定 who 下的三层结果 */
function judgeOne(s, who) {
  const r = judge.scoreSms(s.text, who, false);
  const stage = RANK[r.stage] == null ? 0 : RANK[r.stage];
  return {
    score: r.score,
    stage: r.stage,
    level: r.level,
    // server.js:614 无模型时的兜底口径
    isFraud: r.level !== 'none',
    // server.js:658 真正推给子女端的条件
    push: r.level !== 'none' && stage >= 3,
    hits: r.hits.map(h => h.word),
  };
}

function run(who, label) {
  const fraud = rows.filter(r => r.label === 1).map(r => judgeOne(r, who));
  const norm = rows.filter(r => r.label === 0).map(r => ({ id: r.id, ...judgeOne(r, who) }));

  const fn = fraud.filter(x => !x.isFraud).length;      // 漏报（判为诈骗这一层）
  const tpPush = fraud.filter(x => x.push).length;      // 推得出去的
  const fp = norm.filter(x => x.isFraud).length;        // 误报（判定层）
  const fpPush = norm.filter(x => x.push).length;       // 误报（推送层）

  console.log(`■ who=${who ? `'${who}'` : "（空，老版本 App 与评估脚本的默认）"}`);
  console.log(`  判定层 isFraud(=level!=='none')  漏报 ${fn}/${fraud.length} = ${pct(fn, fraud.length)}   误报 ${fp}/${norm.length} = ${pct(fp, norm.length)}`);
  console.log(`  推送层 isFraud && stage>=3        漏报 ${fraud.length - tpPush}/${fraud.length} = ${pct(fraud.length - tpPush, fraud.length)}   误报 ${fpPush}/${norm.length} = ${pct(fpPush, norm.length)}`);
  const dist = {};
  for (const x of norm) dist[x.level] = (dist[x.level] || 0) + 1;
  console.log(`  正常样本落级：${JSON.stringify(dist)}  最高分 ${Math.max(...norm.map(x => x.score))}  进入 S1 的：${norm.filter(x => x.isFraud).map(x => `${x.id}(S${RANK[x.stage]})`).join(' ') || '无'}`);
  console.log('');
  return { norm, fraud };
}

console.log('=== 本地复算（不联网）===\n');
run('', '空');
run('stranger', '陌生人');

console.log('=== 说明 ===');
console.log('「推送层」才是子女端真正收到提醒的门槛（server.js:658）。');
console.log('评估报告里 18.9% / 14.6% 是「判定层」口径，不能叫「命中即推送子女」。');
