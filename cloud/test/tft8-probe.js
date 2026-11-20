'use strict';
/**
 * tft8-probe.js —— 用当前 3.6/220 词表跑 Telecom_Fraud_Texts_8 的 11,253 条诈骗话术
 *
 * 目的不是算准确率（这批是独白、是生成的、没有正常样本），而是回答一个问题：
 *   我们 220 个词，能认出这 1 万多条真实话术里的多少？漏掉的都在说什么？
 *
 * 用法：
 *   node cloud/test/tft8-probe.js              # 汇总
 *   node cloud/test/tft8-probe.js --show 5     # 每类打印 5 条漏掉的原文
 */
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const gi = (k, d) => { const i = args.indexOf('--' + k); return i >= 0 ? args[i + 1] : d; };
const SHOW = Number(gi('show', '0'));
const DIR = gi('dir', 'D:/华为ict/外部数据集/Telecom_Fraud_Texts_8/Telecom_Fraud_Texts_8-main');

const ROOT = path.join(__dirname, '..');
const { Judge } = require(path.join(ROOT, 'src', 'Judge.js'));
const rules = JSON.parse(fs.readFileSync(path.join(ROOT, 'config', 'rules.json'), 'utf8'));
const j = new Judge(rules);

const KW = new Set((rules.sms_keywords || []).map(w => String(w).toLowerCase()));

/** 极简 CSV：支持双引号包裹与 "" 转义 */
function parseCsv(txt) {
  const rows = [];
  let row = [], cell = '', q = false;
  for (let i = 0; i < txt.length; i += 1) {
    const c = txt[i];
    if (q) {
      if (c === '"') {
        if (txt[i + 1] === '"') { cell += '"'; i += 1; }
        else q = false;
      } else cell += c;
    } else if (c === '"') q = true;
    else if (c === ',') { row.push(cell); cell = ''; }
    else if (c === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; }
    else if (c === '\r') { /* skip */ }
    else cell += c;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  return rows;
}

const LABEL = {
  1: '公检法', 2: '贷款征信', 3: '冒充客服', 4: '领导熟人',
  5: '刷单兼职', 6: '交友', 7: '游戏',
};

const RANK = { none: 0, quiet: 1, warn: 2, high: 3, block: 4 };

const stat = {};       // 每类：n / 各级别数 / 分数和
const missByLabel = {};// 每类漏掉的原文
const missAll = [];    // 全部漏掉（level=none）的文本
let total = 0;

for (const lab of [1, 2, 3, 4, 5, 6, 7]) {
  const p = path.join(DIR, `label${lab}-new1030.csv`);
  if (!fs.existsSync(p)) continue;
  const raw = fs.readFileSync(p);
  let txt;
  try { txt = raw.toString('utf8').replace(/^\uFEFF/, ''); }
  catch (e) { txt = raw.toString('latin1'); }

  const rows = parseCsv(txt).slice(1).filter(r => r && r[0] && r[0].trim());
  stat[lab] = { n: 0, none: 0, quiet: 0, warn: 0, high: 0, block: 0, sum: 0 };
  missByLabel[lab] = [];

  for (const r of rows) {
    const body = r[0].replace(/\s+/g, ' ').trim();
    if (body.length < 8) continue;
    let res;
    try { res = j.scoreSms(body, '', false); } catch (e) { continue; }
    const lv = res.level || 'none';
    const s = stat[lab];
    s.n += 1; total += 1;
    if (s[lv] == null) s[lv] = 0;
    s[lv] += 1;
    s.sum += Number(res.score || 0);
    if (RANK[lv] <= RANK.quiet) {
      missAll.push(body);
      if (missByLabel[lab].length < SHOW) missByLabel[lab].push(body);
    }
  }
}

// ---------------------------------------------------------------- 汇总
const pad = (x, n) => String(x).padEnd(n);
console.log('');
console.log('用「诈骗话术」当输入跑 scoreSms（who=空、isCode=false）—— 只看召回，不看误报');
console.log('');
console.log(pad('类别', 12) + pad('条数', 7) + pad('block', 7) + pad('high', 7) + pad('warn', 7) + pad('none/quiet', 12) + pad('均分', 6) + '任一级别命中率');
console.log('-'.repeat(78));

let agg = { n: 0, warn: 0, sum: 0 };
for (const lab of Object.keys(stat).sort((a, b) => a - b)) {
  const s = stat[lab];
  const heard = s.n - (s.none || 0) - (s.quiet || 0);
  console.log(
    pad(LABEL[lab] || ('label' + lab), 12) +
    pad(s.n, 7) + pad(s.block || 0, 7) + pad(s.high || 0, 7) + pad(s.warn || 0, 7) +
    pad(((s.none || 0) + (s.quiet || 0)), 12) +
    pad(s.n ? (s.sum / s.n).toFixed(0) : '-', 6) +
    (s.n ? (heard / s.n * 100).toFixed(1) + '%' : '-'));
  agg.n += s.n; agg.warn += heard; agg.sum += s.sum;
}
console.log('-'.repeat(78));
console.log(pad('合计', 12) + pad(agg.n, 7) + ''.padEnd(28) + pad('', 12) + pad(agg.n ? (agg.sum / agg.n).toFixed(0) : '-', 6) + (agg.n ? (agg.warn / agg.n * 100).toFixed(1) + '%' : '-'));

// ---------------------------------------------------------------- 漏掉的高频词
// 只统计「规则一个词都没命中」的样本，找它们共同的 2~4 字串
const cnt2 = new Map(), cnt3 = new Map(), cnt4 = new Map();
const ZH = /[\u4e00-\u9fa5]/;
for (const t of missAll) {
  const s = t.replace(/[^\u4e00-\u9fa5]/g, '');
  for (let n = 2; n <= 4; n += 1) {
    const m = n === 2 ? cnt2 : n === 3 ? cnt3 : cnt4;
    const seen = new Set();
    for (let i = 0; i + n <= s.length; i += 1) {
      const g = s.slice(i, i + n);
      if (!ZH.test(g)) continue;
      if (seen.has(g)) continue;
      seen.add(g);
      m.set(g, (m.get(g) || 0) + 1);
    }
  }
}

console.log('');
console.log(`规则完全没反应的样本：${missAll.length} 条（占 ${(missAll.length / total * 100).toFixed(1)}%）`);
console.log('');

function top(m, n, minDoc) {
  return [...m.entries()]
    .filter(([g, c]) => c >= minDoc && !KW.has(g))
    .sort((a, b) => b[1] - a[1])
    .slice(0, n);
}
const thr = Math.max(30, Math.floor(missAll.length * 0.008));
console.log(`这些样本里出现最多的词组（已排除词表已有的词，门槛：≥${thr} 条样本出现）`);
console.log('');
for (const [name, m] of [['二字', cnt2], ['三字', cnt3], ['四字', cnt4]]) {
  const t = top(m, 18, thr);
  console.log(` ${name}：` + t.map(([g, c]) => `${g}(${c})`).join('  '));
}
console.log('');

if (SHOW > 0) {
  for (const lab of Object.keys(missByLabel).sort((a, b) => a - b)) {
    if (!missByLabel[lab].length) continue;
    console.log('');
    console.log(`=== ${LABEL[lab]} 漏掉的样例 ===`);
    for (const t of missByLabel[lab]) console.log('  · ' + t.slice(0, 130));
  }
}
