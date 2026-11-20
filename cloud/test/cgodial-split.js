'use strict';
/**
 * cgodial-split.js —— 把老师给的 CGoDial 切出「训练负样本」与「留出不训」
 *
 * 依据老师 README 的「建议用法」三条：
 *   1. 从 real 分层抽样，**优先抽规则误报的那批**（最难，信息量最大）
 *   2. 每场景留一部分 real **不参与训练**，单独报「政务热线误报率」
 *   3. simu 是模拟生成，只补量，不用于评估 —— 本脚本不碰
 *
 * 只用 kind=real + text_asr：
 *   - simu 老师明确说不用于评估
 *   - text_asr 才对得上线上 SIS 转写（人工转写太干净，训出来的模型到线上会掉点）
 *
 * 留出规则：每场景 min(--holdout, ceil(n × 0.3))。
 *   zhubao 真实只有 154 条，硬留 100 会只剩 54 条训练，所以小场景按 30% 留。
 *
 * 输出（都不进仓库，放数据目录）：
 *   split/train_neg.jsonl   每行 {text, scene, isFp, score, level}
 *   split/heldout.jsonl     同上，但只用于评估
 *
 * 用法：
 *   node cloud/test/cgodial-split.js --neg 1500
 *   node cloud/test/cgodial-split.js --data D:/path/cgodial_flow_dialogs.jsonl --out D:/path/split
 */
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const gi = (k, d) => { const i = args.indexOf('--' + k); return i >= 0 ? args[i + 1] : d; };

const DATA = gi('data', 'D:/华为ict/外部数据集/cgodial/cgodial_flow_dialogs.jsonl');
const OUT = gi('out', path.join(path.dirname(DATA), 'split'));
const TARGET = Number(gi('neg', '1500'));
const HOLD_MAX = Number(gi('holdout', '100'));

const ROOT = path.join(__dirname, '..');
const { Judge } = require(path.join(ROOT, 'src', 'Judge.js'));
const rules = JSON.parse(fs.readFileSync(path.join(ROOT, 'config', 'rules.json'), 'utf8'));
const j = new Judge(rules);

const FP = new Set(['warn', 'high', 'block']);
const clean = s => String(s || '')
  .replace(/^(客服|来电人)：/gm, '')
  .replace(/\s+/g, ' ')
  .trim();

// ---------------------------------------------------------------- 读入 real
const byScene = {};
for (const line of fs.readFileSync(DATA, 'utf8').split('\n')) {
  if (!line) continue;
  const r = JSON.parse(line);
  if (r.kind !== 'real') continue;
  const text = clean(r.text_asr);
  if (text.length < 40) continue;          // 太短的不像一通完整对话
  (byScene[r.scene] = byScene[r.scene] || []).push({ id: r.id, scene: r.scene, text });
}

// ---------------------------------------------------------------- 每场景留出
// 留出必须跨日期均匀：id 里带日期（shaoxing-20200430.json[...]），
// 直接取前 N 条等于只留最早那批，话术可能和后期不同 → 用等间隔抽样。
const heldout = [];
const pool = [];
const stat = {};
for (const scene of Object.keys(byScene).sort()) {
  const arr = byScene[scene].sort((a, b) => String(a.id).localeCompare(String(b.id)));
  const nHold = Math.min(HOLD_MAX, Math.ceil(arr.length * 0.3));
  const step = arr.length / nHold;
  const holdIdx = new Set();
  for (let i = 0; i < nHold; i += 1) holdIdx.add(Math.floor(i * step));
  const h = arr.filter((_, i) => holdIdx.has(i));
  const p = arr.filter((_, i) => !holdIdx.has(i));
  heldout.push(...h);
  pool.push(...p);
  stat[scene] = { real: arr.length, holdout: h.length, pool: p.length };
}

// ---------------------------------------------------------------- 打分+去重
for (const x of pool.concat(heldout)) {
  const s = j.scoreSms(x.text, '', false);
  x.score = s.score;
  x.level = s.level;
  x.stage = s.stage;
  x.isFp = FP.has(s.level);
  x.hits = (s.hits || []).filter(h => h.weight > 0).map(h => h.word);
}

const seen = new Set();
const dedupe = arr => arr.filter(x => {
  // 用全文去重：gov 所有对话开头是同一句客服开场白，只比前 N 字会把不同对话误判成重复
  if (seen.has(x.text)) return false;
  seen.add(x.text);
  return true;
});

// ---------------------------------------------------------------- 抽样
const fpAll = pool.filter(x => x.isFp);
const okAll = pool.filter(x => !x.isFp);
const picked = [...fpAll];
const slot = Math.max(0, TARGET - picked.length);

// 按场景剩余量等比补正常样本
const restByScene = {};
for (const x of okAll) (restByScene[x.scene] = restByScene[x.scene] || []).push(x);
const restTotal = okAll.length;
for (const scene of Object.keys(restByScene).sort()) {
  const share = Math.round(slot * restByScene[scene].length / Math.max(restTotal, 1));
  picked.push(...restByScene[scene].slice(0, share));
}

const train = dedupe(picked).slice(0, Math.max(TARGET, fpAll.length));
const hold = dedupe(heldout);

// ---------------------------------------------------------------- 写出
fs.mkdirSync(OUT, { recursive: true });
const dump = (arr, name) => {
  const p = path.join(OUT, name);
  fs.writeFileSync(p, arr.map(x => JSON.stringify({
    text: x.text, scene: x.scene, isFp: x.isFp, score: x.score, level: x.level, stage: x.stage,
  })).join('\n') + '\n', 'utf8');
  return p;
};
dump(train, 'train_neg.jsonl');
dump(hold, 'heldout.jsonl');

// ---------------------------------------------------------------- 报告
const pad = (s, n) => String(s).padEnd(n);
console.log('\n场景       真实  留出  可训练   其中误报');
console.log('-'.repeat(46));
for (const s of Object.keys(stat).sort()) {
  const st = stat[s];
  const fp = pool.filter(x => x.scene === s && x.isFp).length;
  console.log(pad(s, 10), pad(st.real, 6), pad(st.holdout, 6), pad(st.pool, 8), fp);
}
const tFp = train.filter(x => x.isFp).length;
console.log('-'.repeat(46));
console.log(`训练负样本 ${train.length} 条（误报 ${tFp} 条已优先收入）  留出 ${hold.length} 条`);

const lv = {};
for (const x of hold) lv[x.level] = (lv[x.level] || 0) + 1;
console.log(`留出集 level 分布：${Object.entries(lv).map(([k, v]) => k + ':' + v).join('  ')}`);
console.log(`\n已写：\n  ${path.join(OUT, 'train_neg.jsonl')}\n  ${path.join(OUT, 'heldout.jsonl')}`);
