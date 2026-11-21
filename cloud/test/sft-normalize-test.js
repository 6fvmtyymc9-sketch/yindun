#!/usr/bin/env node
/**
 * Sft.js 的 /judge 返回归一化单测
 *
 * 为什么要单独测它：
 *   融合层拿到的 sftP 直接决定 stage 抬不抬。归一化出错的后果不是崩溃，
 *   而是**静默错判** —— 比如把 skipped（有声不足 1 秒、模型根本没判）
 *   当成"判了没事，p=0"，或者把 confidence 缺省当成 0 分。
 *   这两种都会让第三票在关键时刻失效而不报警。
 *
 * 用法：node cloud/test/sft-normalize-test.js
 */
'use strict';
const { Sft } = require('../src/Sft');

const sft = new Sft({ SFT_URL: 'http://127.0.0.1:8099' });
const n = (o) => sft._normJudge(o, 'audio');

let pass = 0;
let fail = 0;
function check(name, cond, extra) {
  if (cond) {
    pass++;
    console.log('  ✓ ' + name);
  } else {
    fail++;
    console.log('  ✗ ' + name + (extra ? '  → ' + extra : ''));
  }
}

console.log('一、必须当"缺席"的输入（返回 null，不参与融合）');
check('null', n(null) === null);
check('ok=false', n({ ok: false, msg: 'x' }) === null);
check('有声不足 1 秒 skipped=true → 缺席（不是判了没事）', n({ ok: true, skipped: true, p: null, is_fraud: null }) === null);
check('is_fraud 缺失', n({ ok: true, p: 0.9, confidence: 0.9 }) === null);
check('p 与 confidence 都缺失', n({ ok: true, is_fraud: true }) === null);
check('p=NaN', n({ ok: true, is_fraud: true, p: NaN }) === null);

console.log('\n二、正常返回（判诈骗）');
const a = n({ ok: true, is_fraud: true, confidence: 0.98, p: 0.98, scene: '诈骗电话', reason: '冒充公检法', verdict: 'fraud', ms: 3700 });
check('p=0.98', a && a.p === 0.98, a && String(a.p));
check('isFraud=true', a && a.isFraud === true);
check('scene 透传', a && a.scene === '诈骗电话');
check('ms 透传', a && a.ms === 3700);

console.log('\n三、正常返回（判正常，p 是 1-confidence）');
const b = n({ ok: true, is_fraud: false, confidence: 0.85, p: 0.15, scene: '外卖服务', verdict: 'normal' });
check('p=0.15', b && b.p === 0.15, b && String(b.p));
check('isFraud=false', b && b.isFraud === false);

console.log('\n四、服务端没给 p 时要能从 confidence 换算');
const c1 = n({ ok: true, is_fraud: true, confidence: 0.9 });
check('判诈骗 → p=confidence=0.9', c1 && c1.p === 0.9, c1 && String(c1.p));
const c2 = n({ ok: true, is_fraud: false, confidence: 0.8 });
check('判正常 → p=1-confidence=0.2', c2 && Math.abs(c2.p - 0.2) < 1e-9, c2 && String(c2.p));

console.log('\n五、边界值不许溢出 0~1');
const d1 = n({ ok: true, is_fraud: true, p: 1.4 });
check('p=1.4 被夹到 1', d1 && d1.p === 1, d1 && String(d1.p));
const d2 = n({ ok: true, is_fraud: false, p: -0.3 });
check('p=-0.3 被夹到 0', d2 && d2.p === 0, d2 && String(d2.p));

console.log('\n六、未配置 SFT_URL 时一律不出结论');
const off = new Sft({ SFT_URL: '' });
check('enabled=false', off.enabled === false);
off.judgeAudio({ audio: Buffer.from('x') }).then((r) => {
  check('judgeAudio 返回 null', r === null);
  console.log('\n结果：通过 ' + pass + ' 条，失败 ' + fail + ' 条');
  process.exit(fail ? 1 : 0);
});
