'use strict';
/**
 * eval-baseline.js —— 老师《SFT 下一步计划》第 2 步「测基线」的执行脚本
 *
 * 干什么：把 cloud/test/calls_eval/ 里冻结好的 30 通录音逐条打线上 /api/callaudio，
 *        收集「转写字数 / 判定阶段 / 等级 / 类型 / 判据来源」，算出召回与误报，
 *        输出一张对比表 + out-baseline.json。
 *
 * 为什么必须有它：第 1 步（30 通真人录音）一到位，如果还要现写脚本，就等于把
 *        等录音的时间又浪费一遍。这个脚本现在就写好，录音一放进来跑一条命令出表。
 *
 * 用法：
 *   node test/eval-baseline.js                     # 跑 calls_eval（正式评估集）
 *   node test/eval-baseline.js --limit 5           # 只跑前 5 条（省成本、先冒烟）
 *   node test/eval-baseline.js --base http://123.249.25.139:8080
 *   node test/eval-baseline.js --dir D:/some/audio # 冒烟模式：拿任意目录试跑，不当评估集
 *
 * 口径（写进报告前先看清，别混）：
 *   宽松召回 = 诈骗样本判到 warn 及以上（会提醒老人 + 推子女）
 *   严格召回 = 诈骗样本判到 block（会盖红屏）
 *   误报     = 正常样本判到 warn 及以上
 *
 * 成本：每条约 ¥0.001（SIS 转写 + DeepSeek 判定），30 条合计不到 5 分钱。
 * 一律带 dryRun=true —— 判定和花费照走，但不写库、不生成预警、不推子女端。
 */

const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const getArg = (k, d) => {
  const i = args.indexOf('--' + k);
  return i >= 0 && args[i + 1] ? args[i + 1] : d;
};

const BASE = getArg('base', 'http://123.249.25.139:8080').replace(/\/+$/, '');
const LIMIT = Number(getArg('limit', '0')) || 0;
const DIR_OVERRIDE = getArg('dir', '');

const EVAL_DIR = path.join(__dirname, 'calls_eval');
const WORK_DIR = DIR_OVERRIDE ? path.resolve(DIR_OVERRIDE) : EVAL_DIR;
const LABELS = path.join(WORK_DIR, 'labels.json');
const OUT = path.join(WORK_DIR, 'out-baseline.json');

const RANK = { none: 0, watch: 1, warn: 2, high: 3, block: 4 };
const AUDIO_EXT = ['.mp3', '.wav', '.amr', '.m4a', '.aac'];

/** 载入待跑条目：正式模式读 labels.json；冒烟模式扫目录，标签记「未标注」 */
function loadItems() {
  if (!DIR_OVERRIDE) {
    if (!fs.existsSync(LABELS)) {
      console.error(`找不到 ${LABELS}`);
      process.exit(1);
    }
    const meta = JSON.parse(fs.readFileSync(LABELS, 'utf8'));
    if (!meta.items || meta.items.length === 0) {
      console.error('labels.json 里 items 还是空的 —— 第 1 步（30 通录音）还没到位。');
      console.error('要用现有音频试跑脚本，加 --dir <音频目录>（冒烟模式，结果不能当基线）。');
      process.exit(1);
    }
    console.log(`评估集：${meta.items.length} 条，frozen=${meta.frozen === true}`);
    if (meta.frozen !== true) {
      console.log('⚠️  labels.json 的 frozen 还不是 true —— 跑出来的数只能内部看，不能写进材料。');
    }
    return meta.items.map((it) => ({ ...it, file: path.join(WORK_DIR, it.file) }));
  }

  // 冒烟模式：按文件名里的「正常 / 诈骗」猜标签，猜不出记「未标注」
  const files = fs.readdirSync(WORK_DIR)
    .filter((f) => AUDIO_EXT.includes(path.extname(f).toLowerCase()))
    .sort();
  console.log(`冒烟模式：${WORK_DIR} 下 ${files.length} 个音频。标签靠文件名猜，结果不能当基线。`);
  return files.map((f) => ({
    file: path.join(WORK_DIR, f),
    label: f.includes('诈骗') ? '诈骗' : (f.includes('正常') ? '正常' : '未标注'),
    scenario: '冒烟',
    speaker: '',
    date: '',
    duration_sec: 0,
    _smoke: true,
  }));
}

async function one(item) {
  const buf = fs.readFileSync(item.file);
  const name = path.basename(item.file);
  if (buf.length > 25 * 1024 * 1024) {
    return { name, error: `文件 ${(buf.length / 1048576).toFixed(1)}MB 超过 25MB 上限` };
  }
  const body = {
    // 必须用 'demo'：CallAudio.js 第 100 行规定，没有老人端 token 时只接受公开演示编号，
    // 传别的值直接 401「需要老人端身份」。dryRun 已经保证不写库、不推送，用 demo 不污染数据。
    elderId: 'demo',
    number: '1380000' + String(1000 + Math.floor(Math.random() * 8999)), // 陌生号，走完整判定
    name: '',
    durSec: Number(item.duration_sec) || 0,
    audio: buf.toString('base64'),
    dryRun: true,
  };

  const t0 = Date.now();
  let res, text;
  try {
    res = await fetch(BASE + '/api/callaudio', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    text = await res.text();
  } catch (e) {
    return { name, error: '请求失败：' + e.message };
  }
  const ms = Date.now() - t0;

  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON，下面按原文报错 */ }
  if (!json) return { name, error: `HTTP ${res.status} 非 JSON：${text.slice(0, 120)}` };
  if (!res.ok) return { name, error: `HTTP ${res.status} ${JSON.stringify(json).slice(0, 160)}` };

  const r = json.result || json;
  const asr = r.asr || json.asr || {};
  return {
    name,
    label: item.label,
    scenario: item.scenario || '',
    level: String(r.level || '?'),
    stage: r.stage,
    type: r.type,
    basis: r.basis,
    chars: asr.chars == null ? null : asr.chars,
    asrMs: asr.ms,
    ms,
    evidence: (r.evidence || []).slice(0, 2),
    reason: String(r.reason || r.why || '').slice(0, 80),
    _smoke: !!item._smoke,
  };
}

function grade(rows) {
  const scored = rows.filter((r) => !r.error && RANK[r.level] != null);
  const fraud = scored.filter((r) => r.label === '诈骗');
  const normal = scored.filter((r) => r.label === '正常');
  const recallAny = fraud.length ? fraud.filter((r) => RANK[r.level] >= RANK.warn).length / fraud.length : null;
  const recallBlock = fraud.length ? fraud.filter((r) => RANK[r.level] >= RANK.block).length / fraud.length : null;
  const fpr = normal.length ? normal.filter((r) => RANK[r.level] >= RANK.warn).length / normal.length : null;
  return { fraud: fraud.length, normal: normal.length, recallAny, recallBlock, fpr };
}
const pct = (x) => (x == null ? '—' : (x * 100).toFixed(1) + '%');

(async () => {
  let items = loadItems();
  if (LIMIT > 0) items = items.slice(0, LIMIT);

  console.log(`目标：${BASE}    条数：${items.length}    一律 dryRun（不写库、不推送）\n`);
  const rows = [];
  for (let i = 0; i < items.length; i++) {
    const it = items[i];
    process.stdout.write(`[${i + 1}/${items.length}] ${path.basename(it.file)} ... `);
    const r = await one(it);
    rows.push(r);
    if (r.error) console.log('✗ ' + r.error);
    else console.log(`${r.level.padEnd(5)} ${r.stage || ''}  转写${r.chars == null ? '?' : r.chars}字  ${(r.ms / 1000).toFixed(1)}s`);
  }

  const ok = rows.filter((r) => !r.error);
  const g = grade(rows);

  console.log('\n================ 明细 ================');
  console.log('标签   等级   阶段  转写  耗时   场景           文件');
  for (const r of rows) {
    if (r.error) { console.log(`ERROR  ${r.name}  ${r.error}`); continue; }
    console.log(
      String(r.label).padEnd(5),
      String(r.level).padEnd(6),
      String(r.stage || '-').padEnd(5),
      String(r.chars == null ? '-' : r.chars).padEnd(5),
      ((r.ms / 1000).toFixed(1) + 's').padEnd(6),
      String(r.scenario || '').padEnd(15),
      r.name
    );
  }

  console.log('\n================ 汇总（规则 + 云端 DeepSeek 两票）================');
  console.log(`样本：诈骗 ${g.fraud} 条 / 正常 ${g.normal} 条（失败 ${rows.length - ok.length} 条已剔除）`);
  console.log(`宽松召回（warn 及以上）：${pct(g.recallAny)}`);
  console.log(`严格召回（block 盖屏）  ：${pct(g.recallBlock)}`);
  console.log(`误报（正常判到 warn）   ：${pct(g.fpr)}`);
  const ms = ok.map((r) => r.ms).sort((a, b) => a - b);
  if (ms.length) {
    console.log(`单条耗时：中位 ${(ms[Math.floor(ms.length / 2)] / 1000).toFixed(1)}s  P90 ${(ms[Math.min(ms.length - 1, Math.floor(ms.length * 0.9))] / 1000).toFixed(1)}s`);
  }
  const chars = ok.map((r) => r.chars).filter((x) => typeof x === 'number');
  if (chars.length) {
    const zero = chars.filter((x) => x < 20).length;
    console.log(`转写质量：中位 ${chars.slice().sort((a, b) => a - b)[Math.floor(chars.length / 2)]} 字，${zero} 条低于 20 字（SIS 没转出内容，判定等于空跑）`);
  }
  console.log('\n⚠️ 本表只有「规则 + 云端」两票。SFT 音频票未接入（SFT_URL 留空），端侧 TinyBERT 仅 ARM 真机启用。');
  if (ok.some((r) => r._smoke)) console.log('⚠️ 本次含冒烟数据（--dir 模式），不得写进材料。');

  fs.writeFileSync(OUT, JSON.stringify({
    generatedAt: new Date().toISOString(),
    base: BASE,
    dryRun: true,
    smoke: ok.some((r) => r._smoke),
    summary: g,
    items: rows,
  }, null, 2), 'utf8');
  console.log(`\n结果已存：${OUT}`);
})();
