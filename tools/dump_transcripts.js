// 导出评估集 30 条的**完整** ASR 转写（/api/callaudio 只回 chars+evidence，回不到全文）
// 走法：直接用 cloud/src/Asr.js（读 .env 凭据），本地调 SIS，wav 自动重采样 16k。
const fs = require('fs');
const path = require('path');

const EVAL = 'D:/华为ict/交付_银盾_GitHub/cloud/test/calls_eval';
const OUTDIR = path.join(EVAL, 'transcripts_asr');

for (const line of fs.readFileSync('D:/华为ict/交付_银盾_GitHub/cloud/.env', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([A-Z0-9_]+)=(.*)$/);
  if (m) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
}
const { Asr } = require('D:/华为ict/交付_银盾_GitHub/cloud/src/Asr.js');

(async () => {
  fs.mkdirSync(OUTDIR, { recursive: true });
  const asr = new Asr();
  const d = JSON.parse(fs.readFileSync(path.join(EVAL, 'labels.json'), 'utf8'));
  const rows = [];
  for (const item of d.items) {
    const name = path.basename(item.file);
    const buf = fs.readFileSync(path.join(EVAL, item.file));
    const t0 = Date.now();
    let r;
    try {
      r = await asr.transcribe({ audio: buf, name, maxSec: 55 });
    } catch (e) {
      console.log(`${name} ERR ${e.message}`);
      continue;
    }
    const text = (r.text || '').trim();
    fs.writeFileSync(path.join(OUTDIR, name.replace(/\.wav$/, '.txt')), text + '\n', 'utf8');
    rows.push({ name, label: item.label, scenario: item.scenario,
                duration: item.duration_sec, chars: text.length,
                parts: r.parts, silentParts: r.silentParts, ms: Date.now() - t0, text });
    console.log(`${item.label} ${item.scenario} ${text.length}字 ${r.parts}段 ${Date.now() - t0}ms | ${text.slice(0, 30)}`);
  }
  fs.writeFileSync(path.join(OUTDIR, '_all.json'), JSON.stringify(rows, null, 2), 'utf8');
  const empty = rows.filter(r => r.chars < 20);
  console.log('done', rows.length, '字数<20:', empty.length, empty.map(r => r.name).join(','));
})().catch(e => console.log('FATAL', e.message));
