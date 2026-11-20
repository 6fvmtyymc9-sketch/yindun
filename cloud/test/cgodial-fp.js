'use strict';
/**
 * cgodial-fp.js —— 用当前规则跑老师那份 CGoDial 难负样本，复核误报率
 *
 * 背景：老师在 ~/data/cgodial/README.md 里给了一张 v3.5 的误报表（gov 真实 14.6%）。
 *       我们现在是 3.6/220 词，且刚上线 ETC 停用通知判据（3.7 步，+15 抬 S3）。
 *       ETC 场景正好有 336 条真实对话，最可能被新判据误伤 —— 这个脚本就是来查它。
 *
 * 口径完全对齐老师：整通对话去掉「客服：/来电人：」前缀拼成一条文本，喂 scoreSms，
 *       who=''、isCode=false；level 到 warn 及以上算误报。
 *
 * 用法：
 *   node cloud/test/cgodial-fp.js                         # 默认跑 text_asr（线上走 ASR）
 *   node cloud/test/cgodial-fp.js --field text            # 改跑人工转写
 *   node cloud/test/cgodial-fp.js --scene etc --kind real # 只看某一片
 *   node cloud/test/cgodial-fp.js --show 15               # 误报明细打印条数
 */
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const gi = (k, d) => { const i = args.indexOf('--' + k); return i >= 0 ? args[i + 1] : d; };

const DATA = gi('data', 'D:/华为ict/外部数据集/cgodial/cgodial_flow_dialogs.jsonl');
const FIELD = gi('field', 'text_asr');
const ONLY_SCENE = gi('scene', '');
const ONLY_KIND = gi('kind', '');
const SHOW = Number(gi('show', '8'));

const ROOT = path.join(__dirname, '..');
const { Judge } = require(path.join(ROOT, 'src', 'Judge.js'));
const rules = JSON.parse(fs.readFileSync(path.join(ROOT, 'config', 'rules.json'), 'utf8'));
const j = new Judge(rules);

const FP = new Set(['warn', 'high', 'block']);
const stat = {};
const words = {};
const bad = [];

const lines = fs.readFileSync(DATA, 'utf8').split('\n').filter(Boolean);
for (const line of lines) {
  const r = JSON.parse(line);
  if (ONLY_SCENE && r.scene !== ONLY_SCENE) continue;
  if (ONLY_KIND && r.kind !== ONLY_KIND) continue;

  const raw = r[FIELD] || '';
  const body = raw.replace(/^(客服|来电人)：/gm, '');
  const s = j.scoreSms(body, '', false);

  const k = `${r.scene}/${r.kind}`;
  const st = stat[k] = stat[k] || { n: 0, warn: 0, high: 0, block: 0, stage: {}, sum: 0 };
  st.n += 1;
  st.sum += s.score;
  st.stage[s.stage] = (st.stage[s.stage] || 0) + 1;
  if (FP.has(s.level)) {
    st.warn += 1;
    if (s.level === 'high') st.high += 1;
    if (s.level === 'block') st.block += 1;
    for (const h of (s.hits || [])) {
      if (h.weight > 0) words[h.word] = (words[h.word] || 0) + 1;
    }
    if (r.kind === 'real' && bad.length < SHOW * 4) {
      bad.push({
        scene: r.scene, score: s.score, level: s.level, stage: s.stage,
        hits: (s.hits || []).map(h => h.word).join('、'),
        head: body.replace(/\s+/g, ' ').slice(0, 90),
      });
    }
  }
}

const pad = (s, n) => String(s).padEnd(n);
console.log('\n场景/类型        条数   误报   rate     均分   S0  S1  S2  S3  S4');
console.log('-'.repeat(78));
const tot = { n: 0, w: 0, block: 0, high: 0 };
for (const k of Object.keys(stat).sort()) {
  const s = stat[k];
  const rate = s.warn / s.n * 100;
  const g = s.stage;
  const n = i => (g['S' + i] || g[i] || 0);   // scoreSms 的 stage 是 'S0'..'S4' 字符串
  console.log(
    pad(k, 15), pad(s.n, 6), pad(s.warn, 6),
    pad(rate.toFixed(1) + '%', 9), pad((s.sum / s.n).toFixed(0), 6),
    pad(n(0), 4), pad(n(1), 4), pad(n(2), 4), pad(n(3), 4), pad(n(4), 4));
  if (k.endsWith('/real')) { tot.n += s.n; tot.w += s.warn; tot.block += s.block; tot.high += s.high; }
}
console.log('-'.repeat(78));
console.log(`真实对话合计：${tot.n} 条，误报 ${tot.w} 条，${(tot.w / tot.n * 100).toFixed(1)}%（其中 high ${tot.high} 条、block ${tot.block} 条）`);

const top = Object.entries(words).sort((a, b) => b[1] - a[1]).slice(0, 15);
console.log('\n触发最多的词（仅统计误报样本里的正权重命中）');
console.log('  ' + top.map(([w, c]) => `${w}:${c}`).join('  '));

console.log('\n真实对话误报明细（前 ' + SHOW + ' 条）');
for (const b of bad.slice(0, SHOW)) {
  console.log(`\n  [${b.scene}] ${b.score}分 ${b.level} S${b.stage}  命中：${b.hits}`);
  console.log(`    ${b.head}`);
}
