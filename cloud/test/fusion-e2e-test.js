'use strict';
/**
 * ============================================================================
 *  fusion-e2e-test.js —— 第三票融合规则的端到端验证
 * ----------------------------------------------------------------------------
 *  单元测试（fusion-rule-test.js）只验证纯函数；这个脚本验证"真的不推"：
 *  真云端 + 真录音 + 真 DeepSeek，只把 SFT 换成一个给固定分的假服务。
 *
 *  起因：老师计划第 0 步的验收要求——「物业停水那段录音，以及把它的 p 手动
 *  改成 0.95 的模拟情况，老人端都不弹提醒；两段诈骗录音结果不变」。
 *  要造"p 手动改成 0.95"，最干净的办法就是让假 SFT 直接返回 0.95。
 *
 *  用法：
 *    node cloud/test/fusion-e2e-test.js 0.95                  # 物业停水 + 假 0.95
 *    node cloud/test/fusion-e2e-test.js 0.706                 # 同一段 + 它的真实分数
 *    node cloud/test/fusion-e2e-test.js 0.95 13700002222_20261109141020.mp3 28.8
 *                                                            # 诈骗录音 + 假 0.95
 *  不带参数时跑"物业停水 + 0.95"，也就是最该守住的那个场景。
 * ============================================================================
 */
const http = require('http');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

const P = Number(process.argv[2] || 0.95);
const FILE = process.argv[3] || '13600003333_20261109160530.mp3';   // 物业停水（正常）
const DUR = Number(process.argv[4] || 20.1);
const NUMBER = FILE.slice(0, 11);

const ROOT = path.join(__dirname, '..');
const SVC_PORT = 8091;
const FAKE_SFT = 8092;
const CALL = path.join(ROOT, '..', 'demo', 'calls', FILE);

if (!fs.existsSync(CALL)) {
  console.error(`找不到录音：${CALL}`);
  process.exit(2);
}

// ---- 假 SFT：不管收到什么，都回同一个 p ----
const fake = http.createServer((req, res) => {
  let buf = '';
  req.on('data', d => { buf += d; });
  req.on('end', () => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, p: P, verdict: P >= 0.5 ? '是' : '否', ms: 3, mode: 'audio' }));
  });
});

fake.listen(FAKE_SFT, async () => {
  console.log(`[假 SFT] :${FAKE_SFT} 固定返回 p=${P}`);
  const svc = spawn(process.execPath, ['src/server.js'], {
    cwd: ROOT,
    env: {
      ...process.env,
      PORT: String(SVC_PORT),
      SFT_URL: `http://127.0.0.1:${FAKE_SFT}`,
      SFT_WAIT_MS: '8000',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  svc.stdout.on('data', d => { log += d; });
  svc.stderr.on('data', d => { log += d; });

  await new Promise(r => setTimeout(r, 6000));   // 等 express + 数据库就绪

  const body = JSON.stringify({
    number: NUMBER, durSec: DUR, name: 'call.mp3',
    audio: fs.readFileSync(CALL).toString('base64'),
  });

  let failed = 0;
  try {
    const t = Date.now();
    const r = await fetch(`http://127.0.0.1:${SVC_PORT}/api/callaudio`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body,
    });
    const j = await r.json();
    const sft = j.sft || {};

    console.log(`\n录音 ${FILE}（${DUR}s）  假 SFT p=${P}`);
    console.log(`  结论      S${j.stage} / ${j.level}   basis=${j.basis}`);
    console.log(`  是否推送  ${j.pushed ? '已推送子女端' : '未推送'}`);
    console.log(`  sft 字段  p=${sft.p} hint=${sft.hint} seconded=${sft.seconded}`);
    console.log(`  耗时      ${((Date.now() - t) / 1000).toFixed(1)} 秒`);

    // ---- 断言：p≥0.85 但结论没到 warn 时，绝不允许推送 ----
    if (P >= 0.85 && j.stage < 2) {
      if (j.pushed) {
        console.log('\n  ✗ 失败：单票高分却推送了，正是不该发生的情况');
        failed++;
      } else if (sft.hint !== true) {
        console.log('\n  ✗ 失败：没推送，但也没留痕（sft.hint 不是 true）');
        failed++;
      } else {
        console.log('\n  ✓ 通过：只有专用模型一家高分 → 不告警，只留痕');
      }
    } else if (j.stage >= 2) {
      if (!j.pushed) {
        console.log('\n  ✗ 失败：到了 warn 却没推送');
        failed++;
      } else {
        console.log('\n  ✓ 通过：结论到 warn 且已推送');
      }
    } else {
      console.log('\n  ✓ 通过：p 未过阈值，规则与大模型结论未受影响');
    }
  } catch (e) {
    console.log('请求失败：', e.message);
    failed++;
  }

  svc.kill();
  fake.close();
  await new Promise(r => setTimeout(r, 300));
  if (failed) {
    console.log('\n--- 服务日志尾部 ---');
    console.log(log.split('\n').slice(-12).join('\n'));
  }
  process.exit(failed ? 1 : 0);
});
