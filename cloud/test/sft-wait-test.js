'use strict';
/**
 * 复盘被第三票拖住多久 —— SFT_WAIT_MS 的作用验证
 * ============================================================================
 * 【为什么要单独测这个】
 *   SFT 正常时单次 114~148 ms，看不出上限有什么意义；真出事的是"连得上、不回话"
 *   ——权重还在加载、显存被别的进程占了、请求卡在队列里。那时候复盘会一直挂到
 *   SFT_WAIT_MS 用尽才出结论，而老人这边只是觉得"挂断之后手机没反应"。
 *
 *   手机端读超时 90 秒（CallRecord.java），且超时只写日志、不提示，
 *   所以这个上限必须远小于 90 秒。默认 60000 是照 CPU 部署写的，GPU 上不该沿用。
 *
 * 【怎么造这个现场】
 *   起一个只接受连接、永远不回话的 TCP 服务冒充 SFT，然后让它跑真云端 + 真录音。
 *   一个进程搞定，不依赖外部服务，也不用动 .env。
 *
 * 用法：
 *   node cloud/test/sft-wait-test.js 8000     # 期望 ~8 秒 + 转写/大模型的 3 秒
 *   node cloud/test/sft-wait-test.js 60000    # 复现 63 秒那种情况
 */
const net = require('net');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

const WAIT = Number(process.argv[2] || 8000);
const ROOT = path.join(__dirname, '..');
const PORT = 8087;
const FAKE_SFT = 8099;
const CALL = path.join(ROOT, '..', 'demo', 'calls', '13700002222_20261109141020.mp3');

const hang = net.createServer(s => {
  s.on('data', () => { });     // 收下请求，不回一个字节
  s.on('error', () => { });
});

hang.listen(FAKE_SFT, async () => {
  console.log(`[假 SFT] ${FAKE_SFT} 已监听（只接连接、不回话）`);
  const svc = spawn(process.execPath, ['src/server.js'], {
    cwd: ROOT,
    env: { ...process.env, PORT: String(PORT), SFT_URL: `http://127.0.0.1:${FAKE_SFT}`, SFT_WAIT_MS: String(WAIT) },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  svc.stdout.on('data', () => { });
  svc.stderr.on('data', d => process.stderr.write(d));

  await new Promise(r => setTimeout(r, 5000));   // 等 express 起来

  const audio = fs.readFileSync(CALL);
  const body = JSON.stringify({
    number: '13700002222', durSec: 28.8, name: 'call.mp3',
    audio: audio.toString('base64'),
  });

  const t = Date.now();
  try {
    const r = await fetch(`http://127.0.0.1:${PORT}/api/callaudio`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body,
    });
    const j = await r.json();
    console.log(`SFT_WAIT_MS=${WAIT} → 耗时 ${((Date.now() - t) / 1000).toFixed(1)} 秒`
      + ` ｜ stage ${j.stage}/${j.level} ｜ sftState ${j.sftState}`);
    console.log('第三票缺席时结论仍由规则 + DeepSeek 给出，这是降级成功的表现。');
  } catch (e) {
    console.log('请求失败：', e.message);
  }
  svc.kill();
  hang.close();
  process.exit(0);
});
