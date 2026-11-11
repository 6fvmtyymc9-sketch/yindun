'use strict';
/**
 * ecs-callaudio.js —— 从本机直接打线上 ECS 的通话复盘接口
 *
 * 为什么需要它：切段 + SIS 是这一轮新加的能力，本地能跑通不代表 ECS 上能跑通——
 * ECS 的 .env、node 版本、到 SIS 的网络都跟本机不一样。部署完必须用真实录音验一次。
 *
 * 用法：node test/ecs-callaudio.js <本地录音.mp3> [ECS 地址]
 */

const fs = require('fs');
const path = require('path');

const file = process.argv[2];
const base = process.argv[3] || 'http://123.249.25.139:8080';
if (!file) {
  console.error('用法：node test/ecs-callaudio.js <录音.mp3> [http://ip:port]');
  process.exit(1);
}

const buf = fs.readFileSync(file);
const name = path.basename(file);
const number = name.split('_')[0];

// 通话时长：优先用同名 .txt 里的元信息没有就按文件大小估。
// 这里只影响界面上的"通话 2 分 08 秒"，判定用的是音频本身。
const durSec = Number(process.argv[4] || 0);

const body = {
  elderId: 'demo',
  number,
  name: '',
  durSec,
  audio: buf.toString('base64'),
};

(async () => {
  const t0 = Date.now();
  console.log(`→ ${base}/api/callaudio  ${name} (${(buf.length / 1024).toFixed(1)} KB)`);
  const res = await fetch(base + '/api/callaudio', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const ms = Date.now() - t0;
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = null; }

  console.log(`← HTTP ${res.status}  ${(ms / 1000).toFixed(1)} 秒`);
  if (!json) { console.log(text.slice(0, 800)); return; }

  const r = json.result || json;
  const asr = r.asr || json.asr || {};
  console.log('--- 转写 ---');
  console.log('source =', asr.source, '| parts =', asr.parts, '| durationSec =', asr.durationSec);
  console.log('text   =', String(asr.text || json.text || '').slice(0, 300));
  console.log('--- 判定 ---');
  console.log('stage =', r.stage, '| level =', r.level, '| type =', r.type, '| basis =', r.basis);
  console.log('reason=', String(r.reason || r.why || '').slice(0, 300));
  if (json.why) console.log('why   =', String(json.why).slice(0, 300));
})();
