'use strict';
/**
 * cgodial-fusion.js —— 用「线上通话链路的两票融合口径」跑 CGoDial 真实政务热线
 *
 * ============================================================================
 * 为什么要做这件事
 *
 *   11-20 只测了**规则单票**：3,936 条真实对话误报 460 条（11.7%），其中 111 条
 *   被判到 block（会盖红屏）。见 docs/CGoDial难负样本复核_20261120.md。
 *
 *   但线上跑的不是规则单票。CallAudio.js 第 160~180 行的融合是：
 *       stage = AI 的 stage            ← AI 说了算
 *       只有「规则判 S4 且 AI 判 <2」时才抬到 2（规则托底，防 AI 漏报）
 *   AI 挂掉才退回规则单票。
 *
 *   所以真正该问的是：**规则那 460 条误报，DeepSeek 能不能救回来？**
 *   —— 这是线上两票的真实误报，从来没测过。本脚本就是来补这个数。
 *
 * ============================================================================
 * 口径（照抄，不自己发明）
 *
 *   · 输入一律 text_asr（线上走 SIS 转写），去掉「客服：/来电人：」前缀
 *   · 只跑 kind=real（simu 是模拟生成，不用于评估）
 *   · 规则：scoreSms(body, '', false) —— who 传空，与老师口径一致
 *   · AI：Pangu.judgeCall(transcript) —— 通话链路的同一个方法，不是 reviewSms
 *   · 融合：逐字照抄 CallAudio.js
 *   · 误报口径：最终 stage >= 2（warn 及以上）
 *
 * ============================================================================
 * 分组设计（不是随便抽）
 *
 *   fp 组：规则已判 warn 及以上（460 条，**全跑，不抽样**）
 *          → 看 AI 救回多少。这是全部 population，不需要抽样误差。
 *   base 组：规则判 S0（3,024 条里分层抽 N 条，默认 200）
 *          → 看 AI 会不会**自己**把正常对话抬上去（规则没事、AI 报了的才是纯 AI 误报）
 *
 *   两组都要，否则只能看到"救回"，看不到"新增"。
 *
 * ============================================================================
 * 用法
 *
 *   node cloud/test/cgodial-fusion.js --limit 3          # 冒烟，只跑 3 条
 *   node cloud/test/cgodial-fusion.js --group fp         # 只跑误报组 460 条
 *   node cloud/test/cgodial-fusion.js                    # 两组都跑（460 + 200）
 *   node cloud/test/cgodial-fusion.js --conc 8 --n 300   # 加并发、加对照
 *
 * 产物：cloud/test/calls_eval/out-cgodial-fusion.jsonl（逐条）+ 终端统计
 */
require('../src/env');
const fs = require('fs');
const path = require('path');
const { Judge } = require('../src/Judge');
const { Pangu } = require('../src/Pangu');

const args = process.argv.slice(2);
const gi = (k, d) => { const i = args.indexOf('--' + k); return i >= 0 ? args[i + 1] : d; };

const DATA = gi('data', 'D:/华为ict/外部数据集/cgodial/cgodial_flow_dialogs.jsonl');
const GROUP = gi('group', 'both');           // fp | base | both
const N_BASE = Number(gi('n', '200'));
const CONC = Math.max(1, Number(gi('conc', '3')));
const TRIES = Math.max(1, Number(gi('tries', '4')));
/* 必须限速：DeepSeek 侧硬限流是 **每秒 4 次、每分钟 100 次**（2026-11-21 实测）。
   11-21 第一轮用 8 并发打爆过，660 条里 466 条的 AI 票根本没调通，
   退回规则单票后被算成误报 —— 那个 48% 的"误报率"是我的并发造成的假象。
   全局节流到 1.4 次/秒（= 84 次/分钟），稳在两条限制下面。 */
const MIN_GAP = Number(gi('gap', '700'));
const LIMIT = Number(gi('limit', '0'));
/* 注意：不要写进 calls_eval/ —— 那个目录 2026-11-21 已冻结，只读。
   CGoDial 是另一套数据、另一个用途，结果单独放在 test/ 下。 */
const OUT = gi('out', path.join(__dirname, 'out-cgodial-fusion.jsonl'));

const LEVEL_OF_STAGE = { 0: 'none', 1: 'watch', 2: 'warn', 3: 'high', 4: 'block' };
const FP_LEVELS = new Set(['warn', 'high', 'block']);

const rules = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'config', 'rules.json'), 'utf8'));
const judge = new Judge(rules);
const pangu = new Pangu();

if (!pangu.enabled) {
  console.error('❌ MAAS_KEY 未配置，AI 这一票调不出去。检查 cloud/.env');
  process.exit(1);
}

// ---------- 1. 算规则分并分组 ----------
const all = fs.readFileSync(DATA, 'utf8').split('\n').filter(Boolean).map((l) => {
  const r = JSON.parse(l);
  const body = String(r.text_asr || '').replace(/^(客服|来电人)：/gm, '');
  const s = judge.scoreSms(body, '', false);
  return {
    id: r.id,
    kind: r.kind,
    scene: r.scene,
    body,
    chars: body.length,
    localScore: s.score,
    localStage: Number(String(s.stage).replace('S', '')) || 0,
    localLevel: s.level,
    localHits: (s.hits || []).filter((h) => h.weight > 0).map((h) => h.word),
  };
}).filter((r) => r.kind === 'real' && r.body.length >= 20);   // 只评估真实对话；simu 是模拟生成，只补量不用于评估

const fpGroup = all.filter((r) => r.localStage >= 2);
const basePool = all.filter((r) => r.localStage === 0);

/** 按场景分层、场景内按 id 等距抽样 —— 确定性，同样参数每次抽到的完全一样 */
function stratified(arr, n) {
  const byScene = {};
  for (const r of arr) (byScene[r.scene] = byScene[r.scene] || []).push(r);
  const out = [];
  for (const k of Object.keys(byScene).sort()) {
    const list = byScene[k].sort((a, b) => String(a.id).localeCompare(String(b.id)));
    const want = Math.max(1, Math.round((n * list.length) / arr.length));
    const step = list.length / want;
    for (let i = 0; i < want && i < list.length; i++) out.push(list[Math.floor(i * step)]);
  }
  return out.slice(0, n);
}

let tasks = [];
if (GROUP === 'fp' || GROUP === 'both') tasks = tasks.concat(fpGroup.map((r) => ({ ...r, group: 'fp' })));
if (GROUP === 'base' || GROUP === 'both') tasks = tasks.concat(stratified(basePool, N_BASE).map((r) => ({ ...r, group: 'base' })));
if (LIMIT) tasks = tasks.slice(0, LIMIT);

console.log(`[准备] 数据真对话 ${all.length} 条`);
console.log(`[准备] fp 组（规则已误报）${fpGroup.length} 条 / base 对照组（规则 S0）池 ${basePool.length} 条`);
console.log(`[准备] 本次要跑 ${tasks.length} 条，并发 ${CONC}，模型 ${pangu.model}`);
console.log(`[准备] 输出 ${OUT}\n`);

// ---------- 2. 融合（照抄 CallAudio.js 第 160~180 行） ----------
function fuse(localStage, ai) {
  let stage;
  let basis;
  if (ai) {
    stage = ai.stage;
    basis = 'ai';
    if (localStage >= 4 && stage < 2) {
      stage = 2;
      basis = 'ai+rule-floor';
    }
  } else {
    stage = localStage;
    basis = 'rule';
  }
  return { stage, basis };
}

// ---------- 3. 并发跑 ----------
const started = Date.now();
const results = new Array(tasks.length);
let done = 0;

/* 全局节流器：保证任意两次请求之间至少隔 MIN_GAP 毫秒 */
let lastReq = 0;
async function throttle() {
  const now = Date.now();
  const wait = Math.max(0, lastReq + MIN_GAP - now);
  lastReq = Math.max(now, lastReq + MIN_GAP);
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
}

async function worker() {
  while (true) {
    const idx = done;
    if (idx >= tasks.length) return;
    done += 1;
    const t = tasks[idx];
    const t0 = Date.now();
    let ai = null;
    let err = null;
    let tries = 0;
    // judgeCall 内部把异常都吃掉了，只返回 null。两种原因混在一起：
    //   ① 模型吐了坏 JSON（字符串里没转义引号）
    //   ② 被限流 429
    // 两者都靠重试解决。重试到 4 次仍失败才认缺席。
    while (tries < TRIES && !ai) {
      tries += 1;
      await throttle();
      try {
        ai = await pangu.judgeCall(t.body);
      } catch (e) {
        err = e.message;
      }
      if (!ai && tries < TRIES) await new Promise((r) => setTimeout(r, 600 * tries));
    }
    if (!ai) err = err || 'judgeCall 返回 null（坏 JSON 或被限流，已重试 ' + TRIES + ' 次）';
    const f = fuse(t.localStage, ai);
    results[idx] = {
      id: t.id,
      group: t.group,
      kind: t.kind,
      scene: t.scene,
      chars: t.chars,
      localScore: t.localScore,
      localStage: t.localStage,
      localLevel: t.localLevel,
      localHits: t.localHits,
      aiStage: ai ? ai.stage : null,
      aiType: ai ? ai.type : null,
      aiReason: ai ? ai.reason : null,
      aiErr: err,
      finalStage: f.stage,
      finalLevel: LEVEL_OF_STAGE[f.stage],
      isFp: FP_LEVELS.has(LEVEL_OF_STAGE[f.stage]),
      basis: f.basis,
      ms: Date.now() - t0,
      cost: pangu.lastCost || 0,
    };
    const n = results.slice(0, idx + 1).filter(Boolean).length;
    if (n % 25 === 0 || n === tasks.length) {
      const el = (Date.now() - started) / 1000;
      console.log(`  ${n}/${tasks.length}  ${el.toFixed(0)}s  已花 ¥${pangu.totalCost.toFixed(3)}`);
    }
  }
}

(async () => {
  await Promise.all(Array.from({ length: Math.min(CONC, tasks.length) }, worker));

  const ok = results.filter(Boolean);
  fs.writeFileSync(OUT, ok.map((r) => JSON.stringify(r)).join('\n') + '\n', 'utf8');

  // ---------- 4. 统计 ----------
  const pct = (a, b) => (b ? ((a / b) * 100).toFixed(1) + '%' : '-');
  /* 主指标只算「AI 有结论」的条目。AI 缺席的会退回规则单票，
     把它算进误报率等于把"我没调通"记成"模型判错"。 */
  const withAi = ok.filter((r) => r.aiStage !== null);
  const noAi = ok.filter((r) => r.aiStage === null);

  console.log('\n================ 覆盖率 ================');
  console.log(`  样本 ${ok.length} 条｜AI 有结论 ${withAi.length} 条（${pct(withAi.length, ok.length)}）｜缺席 ${noAi.length} 条`);
  if (noAi.length) {
    console.log(`  ⚠️ 缺席的 ${noAi.length} 条不进主指标（退回规则单票，算进来会把误报率算高）`);
  }

  const fpOnly = withAi.filter((r) => r.group === 'fp');
  const baseOnly = withAi.filter((r) => r.group === 'base');

  console.log('\n================ 结论 ================');
  console.log('【fp 组】规则已判 warn+ 的真实对话，AI 能不能救回来');
  if (fpOnly.length) {
    const rescued = fpOnly.filter((r) => !r.isFp);
    const stillFp = fpOnly.filter((r) => r.isFp);
    const blockNow = fpOnly.filter((r) => r.finalStage >= 4);
    console.log(`  样本 ${fpOnly.length} 条`);
    console.log(`  融合后仍误报：${stillFp.length} 条（${pct(stillFp.length, fpOnly.length)}）`);
    console.log(`  AI 救回：      ${rescued.length} 条（${pct(rescued.length, fpOnly.length)}）`);
    console.log(`  融合后仍 block：${blockNow.length} 条（${pct(blockNow.length, fpOnly.length)}）`);

    const byScene = {};
    for (const r of fpOnly) {
      const k = r.scene;
      byScene[k] = byScene[k] || { n: 0, still: 0, block: 0 };
      byScene[k].n += 1;
      if (r.isFp) byScene[k].still += 1;
      if (r.finalStage >= 4) byScene[k].block += 1;
    }
    console.log('\n  场景        误报样本  融合后仍误报  仍 block');
    for (const k of Object.keys(byScene).sort()) {
      const s = byScene[k];
      console.log(`  ${k.padEnd(11)} ${String(s.n).padStart(8)} ${String(s.still).padStart(13)} ${String(s.block).padStart(9)}`);
    }

    console.log('\n  仍误报的明细（按最终分数降序，前 12 条）');
    for (const r of stillFp.sort((a, b) => b.finalStage - a.finalStage).slice(0, 12)) {
      console.log(`   [${r.scene}] 规则 ${r.localStage}→AI ${r.aiStage}→最终 ${r.finalStage} ${r.finalLevel} | ${(r.aiType || '').slice(0, 12)} | ${(r.aiReason || '').slice(0, 30)}`);
    }
  }

  console.log('\n【base 组】规则判 S0 的正常对话，AI 会不会自己抬上去');
  if (baseOnly.length) {
    const aiFp = baseOnly.filter((r) => r.isFp);
    console.log(`  样本 ${baseOnly.length} 条`);
    console.log(`  AI 独自误报：${aiFp.length} 条（${pct(aiFp.length, baseOnly.length)}）`);
    for (const r of aiFp.slice(0, 8)) {
      console.log(`   [${r.scene}] 规则 ${r.localStage}→AI ${r.aiStage} ${r.finalLevel} | ${(r.aiType || '').slice(0, 12)} | ${(r.aiReason || '').slice(0, 30)}`);
    }
  }

  const totalFp = withAi.filter((r) => r.isFp).length;
  console.log('\n【合计（只算 AI 有结论的）】');
  console.log(`  样本 ${withAi.length} 条，融合后误报 ${totalFp} 条（${pct(totalFp, withAi.length)}）`);
  console.log(`  AI 缺席 ${noAi.length} 条（这部分退回规则单票，其中判成误报 ${noAi.filter((r) => r.isFp).length} 条，未计入上面）`);
  const ms = ok.map((r) => r.ms).sort((a, b) => a - b);
  if (ms.length) console.log(`  单条耗时 中位 ${ms[Math.floor(ms.length / 2)]}ms  P90 ${ms[Math.floor(ms.length * 0.9)]}ms`);
  console.log(`  合计花费 ¥${pangu.totalCost.toFixed(3)}  （${pangu.calls} 次调用）`);
  console.log(`  结果已存：${OUT}`);
})();
