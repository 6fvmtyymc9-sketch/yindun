/**
 * 试探任意录音能不能进 /api/callaudio —— 不做评估、不写库，只看转写与判定。
 *
 *   node cloud/test/probe-audio.js <文件或目录>
 *
 * 用途：拿到一条没标过的录音（微信传来的、手机录的），先看它：
 *   1. 格式/采样率服务端认不认
 *   2. ASR 能不能转出字（转不出来就说明采样率或编码有问题）
 *   3. 规则+AI 给的判定是什么（决定它是正常样本还是诈骗样本）
 *
 * 一律 dryRun=true：判定和花费照走，但不写库、不推送。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const BASE = (process.env.YINDUN_BASE || 'http://123.249.25.139:8080').replace(/\/+$/, '');
const MAX = 25 * 1024 * 1024;

const target = process.argv[2];
if (!target) {
  console.error('用法：node cloud/test/probe-audio.js <文件或目录>');
  process.exit(1);
}

let files = [];
const st = fs.statSync(target);
if (st.isDirectory()) {
  files = fs.readdirSync(target)
    .filter(f => /\.(wav|mp3|m4a|amr|aac|ogg)$/i.test(f))
    .sort()
    .map(f => path.join(target, f));
} else {
  files = [target];
}

async function probe(file) {
  const buf = fs.readFileSync(file);
  const name = path.basename(file);
  if (buf.length > MAX) return { name, error: `${(buf.length / 1048576).toFixed(1)}MB 超过 25MB 上限` };

  const body = {
    elderId: 'demo',
    number: '1380000' + String(1000 + Math.floor(Math.random() * 8999)),
    // 【注意】这个字段业务含义是「来电人姓名」，但 Asr.js 的切段函数拿它当文件名判格式
    // （split(audio, name) → formatOf(name)），传空串就判不出格式、长录音切不动。
    // 这里刻意带上扩展名，先把这段链路验通。
    name: name,
    durSec: 0,
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
  try { json = JSON.parse(text); } catch { /* ignore */ }
  if (!json) return { name, error: `HTTP ${res.status} 非 JSON：${text.slice(0, 150)}`, ms };
  if (!res.ok) return { name, error: `HTTP ${res.status} ${JSON.stringify(json).slice(0, 200)}`, ms };

  // 响应是平铺的：{ ok, stage, level, reason, asr:{...}, local:{...}, meta:{...} }
  const r = json;
  // 接口不返回整篇转写稿，只给字数与证据原话（CallAudio.js 第 340 行附近）
  const asr = r.asr || {};
  return {
    name,
    ms,
    level: r.level || '-',
    stage: r.stage != null ? r.stage : '-',
    stageName: r.stageName || '',
    score: (r.local && r.local.score) != null ? r.local.score : '-',
    isFraud: !!r.isFraud,
    type: r.type || '',
    chars: asr.chars || 0,
    durSec: asr.durationSec,
    parts: asr.parts,
    evidence: r.evidence || [],
    reason: r.reason || '',
    advice: r.advice || '',
    local: r.local || {},
  };
}

(async () => {
  console.log(`目标：${BASE}\n共 ${files.length} 个文件，一律 dryRun\n`);
  for (const f of files) {
    const r = await probe(f);
    if (r.error) {
      console.log(`${r.name}\n  ✗ ${r.error}${r.ms ? `  (${r.ms}ms)` : ''}\n`);
      continue;
    }
    console.log(`${r.name}`);
    console.log(`  ${r.level} / S${r.stage}${r.stageName ? ' ' + r.stageName : ''} / 规则 ${r.score} 分`
      + `   ${r.ms}ms   录音 ${r.durSec || '?'}s 分 ${r.parts || '?'} 段   转写 ${r.chars} 字`);
    if (r.type) console.log(`  类型：${r.type}`);
    if (r.reason) console.log(`  理由：${r.reason.slice(0, 140)}`);
    if (r.evidence && r.evidence.length) console.log(`  原话：${r.evidence.map(s => '「' + s + '」').join(' ').slice(0, 180)}`);
    console.log('');
  }
})();
