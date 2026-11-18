'use strict';
/**
 * ============================================================================
 *  eval-magicdata.js —— 用 MagicData 自带转写稿测「正常通话会不会误报」
 * ----------------------------------------------------------------------------
 *  为什么不用 ASR：
 *    MagicData 两个包（meeting / accent）每个 wav 旁边都有同名 .txt，
 *    里面是带时间戳和说话人的逐句转写。评估要的是"文本 → 规则分"，
 *    转写稿本来就现成，跑 ASR 只是多绕一圈、多一份误差。
 *
 *  为什么要按"一通对话"去重：
 *    accent 包是双路单轨录制（同一通对话，两个人各一个文件）。
 *    直接全部拼接会把同一句话喂两遍，分数虚高一倍。
 *    所以 G0001 那 8 个文件只取一路（SPK 编号小的那路）。
 *
 *  用法：
 *    node test/eval-magicdata.js [数据目录]
 *    数据目录默认读环境变量 MAGICDATA_DIR，再默认 D:/华为ict/外部数据集/magicdata
 *
 *  判读：正常通话应该判到 S0/S1（none / watch）。
 *  到 warn 及以上就是误报 —— 我们会给老人推一条"这通电话有风险"，
 *  而他刚才只是在聊房租。
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');
const { Judge } = require('../src/Judge');

const rules = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'config', 'rules.json'), 'utf8'));
const judge = new Judge(rules);

/** 从 zip 里读不了，这里读的是已解压目录；也接受直接指向 zip 的同名目录 */
function findTxt(dir) {
  const out = [];
  const walk = (d) => {
    for (const n of fs.readdirSync(d)) {
      const p = path.join(d, n);
      const st = fs.statSync(p);
      if (st.isDirectory()) walk(p);
      else if (n.toLowerCase().endsWith('.txt')) out.push(p);
    }
  };
  walk(dir);
  return out;
}

/**
 * 解析一行：`[start,end]  说话人  性别,方言  文本` 或 `[s,e]  SPK002  男  文本`
 * 说话人位是 `0 / none` 的是噪声标记（[ENS]），不是人说的话，丢掉。
 */
function parseTranscript(txt) {
  const lines = txt.split(/\r?\n/);
  const keep = [];
  const speakers = new Set();
  let lastEnd = 0;
  for (const line of lines) {
    if (!line.trim()) continue;
    const m = line.match(/^\[([\d.]+),([\d.]+)\]\s*(.*)$/);
    const rest = m ? m[3] : line;
    if (m) lastEnd = Math.max(lastEnd, parseFloat(m[2]));
    const parts = rest.split('\t');
    if (parts.length >= 3) {
      const who = parts[0].trim();
      if (who === '0' || who.toLowerCase() === 'none') continue;   // [ENS] 噪声
      speakers.add(who);
      keep.push(parts[parts.length - 1].trim());
    } else {
      keep.push(rest.trim());
    }
  }
  return { text: keep.join(' '), speakers: [...speakers], durSec: Math.round(lastEnd) };
}

/** 同一通对话的多路单轨，只留一路，避免重复计分 */
function conversationKey(file) {
  const base = path.basename(file, '.txt');
  // G0001_S0001_0_SPK002 → G0001_S0001_0
  const m = base.match(/^(.*)_SPK\d+$/i);
  return m ? m[1] : base;
}

const LEVEL_ORDER = { none: 0, watch: 1, warn: 2, high: 3, block: 4 };

function main() {
  const dir = process.argv[2] || process.env.MAGICDATA_DIR || 'D:/华为ict/外部数据集/magicdata';
  if (!fs.existsSync(dir)) {
    console.error('找不到数据目录：' + dir);
    console.error('先解压 meeting.zip / accent.zip 到这里，或者用参数指定目录。');
    process.exit(1);
  }
  const files = findTxt(dir);
  if (!files.length) {
    console.error('目录里没有 .txt 转写稿：' + dir);
    process.exit(1);
  }

  // 按"一通对话"分组，每组只留说话人编号最小的那一路
  const groups = new Map();
  for (const f of files) {
    const k = conversationKey(f);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(f);
  }
  const picked = [];
  for (const [k, list] of groups) {
    list.sort();
    picked.push({ key: k, file: list[0], dropped: list.slice(1) });
  }

  console.log('规则版本 ' + rules.version + ' · 关键词 ' + (rules.sms_keywords || []).length);
  console.log('数据目录 ' + dir);
  console.log('转写稿 ' + files.length + ' 份 → 归并后 ' + picked.length + ' 通对话\n');

  const rows = [];
  for (const p of picked) {
    const t = parseTranscript(fs.readFileSync(p.file, 'utf8'));
    // stranger = 最坏情况（这通电话来自陌生号码）。
    // 正常对话在这种前提下都不该报警，所以用它当基准。
    const r = judge.scoreSms(t.text, 'stranger', false);
    rows.push({
      key: p.key,
      chars: t.text.length,
      durMin: +(t.durSec / 60).toFixed(1),
      speakers: t.speakers.length,
      score: r.score,
      level: r.level,
      stage: r.stage,
      hits: r.hits.filter(h => !['陌生号码'].includes(h.word)).slice(0, 4).map(h => h.word + ':' + h.weight),
      dropped: p.dropped.length,
    });
  }

  console.log('对话'.padEnd(22) + '时长'.padStart(7) + '字数'.padStart(8) + '得分'.padStart(6) + '  级别/阶段  命中词');
  console.log('-'.repeat(96));
  for (const r of rows) {
    console.log(
      r.key.slice(0, 21).padEnd(22)
      + (r.durMin + '分').padStart(7)
      + String(r.chars).padStart(8)
      + String(r.score).padStart(6)
      + '  ' + (r.level + '/' + r.stage).padEnd(10)
      + '  ' + (r.hits.length ? r.hits.join(' ') : '—')
    );
  }

  const falseAlarm = rows.filter(r => LEVEL_ORDER[r.level] >= LEVEL_ORDER.warn);
  const watched = rows.filter(r => r.level === 'watch');
  console.log('\n' + '-'.repeat(96));
  console.log('正常对话 ' + rows.length + ' 通，误报（warn 及以上）' + falseAlarm.length + ' 通'
    + '，关注级 ' + watched.length + ' 通'
    + '，无风险 ' + rows.filter(r => r.level === 'none').length + ' 通');
  console.log('总时长 ' + rows.reduce((a, b) => a + b.durMin, 0).toFixed(1) + ' 分钟'
    + '，总字数 ' + rows.reduce((a, b) => a + b.chars, 0));
  if (falseAlarm.length) {
    console.log('\n误报清单（这些是要么改词表、要么改成按句判定，别放着不管）：');
    for (const r of falseAlarm) {
      console.log('  · ' + r.key + '  ' + r.score + '分 ' + r.level + '/' + r.stage + '  ' + r.hits.join(' '));
    }
  } else {
    console.log('全部正常对话都压到了警戒线以下。');
  }
  process.exit(falseAlarm.length ? 2 : 0);
}

main();
