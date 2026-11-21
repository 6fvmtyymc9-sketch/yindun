// 录音入库前的转写核对：逐条送 /api/callaudio（dryRun），输出判定类型 + 关键证据句。
// 为什么用 node：python urllib 走 POST 被前置拦截返回 403，node fetch 已验证可用。
const fs = require('fs');
const path = require('path');
const wave = null; // 时长用文件头解析，不引依赖

const S = 'D:/xwechat_files/wxid_7lumsr2m7fta12_91c9/msg/file/2026-11';
const BASE = 'http://123.249.25.139:8080';
const OUT = 'D:/华为ict/交付包/_outbox/_asr_probe.jsonl';

function wavDur(buf) {
  // 简单 RIFF 解析：找 fmt 块拿字节率，data 块长度 / 字节率
  if (buf.toString('ascii', 0, 4) !== 'RIFF') return 0;
  let off = 12, byteRate = 0, dataLen = 0;
  while (off + 8 <= buf.length) {
    const id = buf.toString('ascii', off, off + 4);
    const size = buf.readUInt32LE(off + 4);
    if (id === 'fmt ') byteRate = buf.readUInt32LE(off + 16);
    if (id === 'data') { dataLen = size; break; }
    off += 8 + size + (size % 2);
  }
  return byteRate ? dataLen / byteRate : 0;
}

async function one(n) {
  const file = path.join(S, `通话录音 ${n}.wav`);
  const buf = fs.readFileSync(file);
  const name = path.basename(file);
  const dur = wavDur(buf);
  const body = {
    elderId: 'demo',
    number: '1390000' + String(1000 + n),
    name,
    durSec: dur,
    audio: buf.toString('base64'),
    dryRun: true,
  };
  const t0 = Date.now();
  try {
    const res = await fetch(BASE + '/api/callaudio', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    const text = await res.text();
    const ms = Date.now() - t0;
    let j = null;
    try { j = JSON.parse(text); } catch {}
    if (!j) return { n, dur, err: `HTTP ${res.status} 非 JSON: ${text.slice(0, 100)}` };
    if (!res.ok) return { n, dur, err: `HTTP ${res.status} ${JSON.stringify(j).slice(0, 120)}` };
    const r = j.result || j;
    return {
      n, dur, ms,
      level: r.level, stage: r.stage, type: r.type, basis: r.basis,
      reason: r.reason, evidence: r.evidence, chars: (j.asr || {}).chars,
      asrText: (j.asr || {}).text || (j.asr || {}).transcript || null,
    };
  } catch (e) {
    return { n, dur, err: e.message };
  }
}

(async () => {
  fs.writeFileSync(OUT, '');
  for (let n = 11; n <= 31; n++) {
    const r = await one(n);
    const line = r.err
      ? `${r.n} ${r.dur.toFixed(1)}s ERR ${r.err}`
      : `${r.n} ${r.dur.toFixed(1)}s ${r.level}/S${r.stage} ${r.type || '-'} | ${(r.evidence || []).slice(0, 2).join(' / ').slice(0, 70)}`;
    console.log(line);
    fs.appendFileSync(OUT, JSON.stringify(r) + '\n');
  }
  console.log('done');
})();
