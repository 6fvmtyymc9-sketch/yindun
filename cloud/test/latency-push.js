'use strict';
/**
 * 端到端推送延迟实测（服务端上报 → SSE 送达到子女端）
 * ============================================================================
 * 计时口径说清楚，避免材料里写模糊：
 *   t0 = 本机 POST /api/alert 发出前的一瞬
 *   t1 = SSE 连接上收到同 alertId 的 alert 事件
 *   t1 - t0 = 落库(await db.addAlert) + 转推(pushTo) + 网络往返
 *
 * **不含**手机侧（老人机本地判定、App 组包、4G 上行）。材料里若写"端到端 < 1 秒"，
 * 必须注明是"服务端上报到子女端收到"这一段的实测，否则是夸大。
 *
 * 用法：node test/latency-push.js [次数] [host]
 */
const http = require('http');

const N = Number(process.argv[2]) || 10;
const HOST = (process.argv[3] || 'http://123.249.25.139:8080').replace(/^https?:\/\//, '');
const [H, P] = HOST.split(':');
const PORT = Number(P) || 80;
const ELDER = 'demo';

const req = (method, path, body) => new Promise((resolve, reject) => {
  const data = body ? Buffer.from(JSON.stringify(body)) : null;
  const r = http.request({
    host: H, port: PORT, path, method,
    headers: data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {},
  }, (res) => {
    let s = '';
    res.on('data', (c) => { s += c; });
    res.on('end', () => resolve({ code: res.statusCode, body: s }));
  });
  r.on('error', reject);
  if (data) r.write(data);
  r.end();
});

const waiters = new Map(); // tag -> resolve
let sseReady = false;

function openSse() {
  return new Promise((resolve, reject) => {
    const r = http.get({ host: H, port: PORT, path: `/api/stream?elderId=${ELDER}` }, (res) => {
      if (res.statusCode !== 200) return reject(new Error('SSE HTTP ' + res.statusCode));
      res.setEncoding('utf8');
      let buf = '';
      res.on('data', (chunk) => {
        buf += chunk;
        let i;
        while ((i = buf.indexOf('\n\n')) >= 0) {
          const ev = buf.slice(0, i);
          buf = buf.slice(i + 2);
          const m = ev.match(/^data:\s*(.+)$/m);
          if (!m) continue;
          let obj;
          try { obj = JSON.parse(m[1]); } catch { continue; }
          if (obj.type !== 'alert') continue;
          const title = String(obj.title || '');
          for (const [tag, fn] of waiters) {
            if (title.includes(tag)) { waiters.delete(tag); fn(performance.now()); }
          }
        }
      });
      sseReady = true;
      resolve(res);
    });
    r.on('error', reject);
    setTimeout(() => { if (!sseReady) reject(new Error('SSE 连接超时')); }, 8000);
  });
}

(async () => {
  const sse = await openSse();
  console.log(`SSE 已连接 ${HOST} elderId=${ELDER}，开始 ${N} 次上报\n`);
  const rows = [];
  for (let i = 0; i < N; i++) {
    const tag = `LAT${Date.now()}${i}`;
    const t0 = performance.now();
    const got = new Promise((res) => waiters.set(tag, res));
    const r = await req('POST', '/api/alert', {
      elderId: ELDER, level: 'warn', title: `${tag} 延迟实测`, body: '自动压测，可删除',
      why: 'latency-probe', channel: 'push',
    });
    if (r.code !== 200) { console.log(`#${i} 上报失败 HTTP ${r.code} ${r.body.slice(0, 120)}`); continue; }
    const t1 = await Promise.race([got, new Promise((res) => setTimeout(() => res(null), 8000))]);
    if (t1 == null) { console.log(`#${i} 8 秒内未收到 SSE`); continue; }
    const ms = t1 - t0;
    rows.push(ms);
    console.log(`#${String(i).padStart(2)}  ${ms.toFixed(1)} ms`);
    await new Promise((r2) => setTimeout(r2, 400));
  }
  sse.destroy();
  if (rows.length) {
    const s = [...rows].sort((a, b) => a - b);
    const pct = (p) => s[Math.min(s.length - 1, Math.floor(p * s.length))];
    const avg = s.reduce((a, b) => a + b, 0) / s.length;
    console.log(`\n样本 ${s.length} 条`);
    console.log(`平均 ${avg.toFixed(1)} ms | 中位 ${pct(0.5).toFixed(1)} ms | P90 ${pct(0.9).toFixed(1)} ms | 最小 ${s[0].toFixed(1)} | 最大 ${s[s.length - 1].toFixed(1)}`);
  }
})();
