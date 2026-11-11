// 用 CDP 驱动 Edge 给演示页截图（可先跑一段 JS 点开界面）。
//
// 为什么不用 `msedge --screenshot`：
//   那个是"load 事件后立刻截"。子女端页面靠 SSE 长连接活着，
//   历史预警还在异步拉取时就截完了 —— 截出来是一片空屏。
//   这里改成：导航 → 等指定毫秒 → 可选执行一段 JS（比如点开时间轴）→ 截图。
//
// 用法：
//   node tools/cdp-shot.js <url> <out.png> [等待毫秒] [要执行的JS]
//
// 例：
//   node tools/cdp-shot.js http://127.0.0.1:8080/ shot.png 3500
//   node tools/cdp-shot.js http://127.0.0.1:8080/ timeline.png 3500 \
//        "document.querySelector('.ev-btn').click(); 'ok'"
//
// 依赖：Windows 上装了 Edge；node 22+（用到内置 WebSocket）。
// 临时配置目录建在系统临时目录下，不污染仓库。
const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

const EDGE = process.env.EDGE_PATH
  || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = Number(process.env.CDP_PORT || 9333);
const [, , URL_ARG, OUT, WAIT, JS] = process.argv;

if (!URL_ARG || !OUT) {
  console.error('用法：node tools/cdp-shot.js <url> <out.png> [waitMs] [jsExpr]');
  process.exit(2);
}

const PROFILE = path.join(os.tmpdir(), 'yindun-cdp-profile');

function get(p) {
  return new Promise((res, rej) => {
    http.get({ host: '127.0.0.1', port: PORT, path: p }, r => {
      let s = '';
      r.on('data', d => (s += d));
      r.on('end', () => res(s));
    }).on('error', rej);
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const child = spawn(EDGE, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${PROFILE}`,
    '--window-size=1500,950',
    'about:blank',
  ], { stdio: 'ignore' });

  let targets = null;
  for (let i = 0; i < 40; i++) {
    try { targets = JSON.parse(await get('/json')); break; } catch (e) { await sleep(250); }
  }
  if (!targets) { console.error('CDP 端口没起来'); child.kill(); process.exit(1); }

  const page = targets.find(t => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 1;
  const pending = new Map();
  const send = (method, params) => new Promise((res, rej) => {
    const myId = id++;
    pending.set(myId, { res, rej });
    ws.send(JSON.stringify({ id: myId, method, params }));
  });

  await new Promise(r => ws.addEventListener('open', r));
  ws.addEventListener('message', ev => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const { res, rej } = pending.get(m.id);
      pending.delete(m.id);
      m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result);
    }
  });

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Page.navigate', { url: URL_ARG });
  await sleep(Number(WAIT || 3000));

  if (JS) {
    const r = await send('Runtime.evaluate', {
      expression: JS, returnByValue: true, awaitPromise: true,
    });
    console.log('EVAL:', JSON.stringify(r.result && r.result.value));
    await sleep(900);      // 等点击触发的异步请求画完
  }

  const shot = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(OUT, Buffer.from(shot.data, 'base64'));
  console.log('已写出', OUT, fs.statSync(OUT).size, 'bytes');

  ws.close();
  child.kill();
  process.exit(0);
})();
