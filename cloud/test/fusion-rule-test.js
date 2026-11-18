'use strict';
/**
 * ============================================================================
 *  fusion-rule-test.js —— 第三票融合规则的单元测试
 * ----------------------------------------------------------------------------
 *  背景：2026-11-18 老师的计划第 0 步指出，原来的写法里 SFT 一家 p≥0.85
 *  就能把结论抬到 warn 并推送，等于"专用模型一票就能让老人收到警告"。
 *  「只抬不降」挡的是漏报，挡不住误报，这是两件事。
 *
 *  改后的规矩：
 *    · sft ≥ 0.85 且 大模型 stage ≥ 1  → 抬到 S2，mark='sft'（两票一致）
 *    · sft ≥ 0.85 但 大模型没附和     → 不抬分，mark='sft-hint'（只留痕）
 *    · sft < 0.85 / 缺席               → 不动作
 *    · 已经是告警（stage ≥ 2）         → 不动作，且绝不因为 sft 分低而降低
 *
 *  跑：node cloud/test/fusion-rule-test.js
 *  不需要网络、不需要模型、不需要起服务。
 * ============================================================================
 */

const assert = require('assert');
const { mergeSft, SFT_HIGH } = require('../src/CallAudio');

let pass = 0;
let fail = 0;

function check(name, got, want) {
  try {
    assert.strictEqual(got.stage, want.stage, `stage 期望 ${want.stage}，实到 ${got.stage}`);
    assert.strictEqual(got.mark, want.mark, `mark 期望 "${want.mark}"，实到 "${got.mark}"`);
    console.log(`  ✓ ${name}`);
    pass++;
  } catch (e) {
    console.log(`  ✗ ${name}\n      ${e.message}`);
    fail++;
  }
}

console.log(`\n第三票融合规则（SFT_HIGH = ${SFT_HIGH}）\n`);

console.log('一、单票不告警（本轮改动的核心）');
// 物业停水那种：规则 0 分、DeepSeek 判正常，只有 SFT 喊高分
check('sft=0.95 + 大模型 S0 + 当前 S0 → 不动，只留痕',
  mergeSft(0, 0, 0.95), { stage: 0, mark: 'sft-hint' });
// 把物业那段的 p 手动改成 0.95，正是老师要求的模拟情况
check('sft=0.95 + 大模型无结论(null) + 当前 S0 → 不动，只留痕',
  mergeSft(0, null, 0.95), { stage: 0, mark: 'sft-hint' });
check('sft=0.95 + 大模型 S0 + 规则已 S1 → 保持 S1，只留痕',
  mergeSft(1, 0, 0.95), { stage: 1, mark: 'sft-hint' });

console.log('\n二、两票一致才抬到 warn');
check('sft=0.95 + 大模型 S1 → 抬到 S2，标 sft',
  mergeSft(0, 1, 0.95), { stage: 2, mark: 'sft' });
check('sft=0.95 + 大模型 S3 + 当前 S1 → 抬到 S2，标 sft',
  mergeSft(1, 3, 0.95), { stage: 2, mark: 'sft' });

console.log('\n三、阈值边界');
check('sft 恰好 0.85 → 算可疑（单票仍只留痕）',
  mergeSft(0, 0, 0.85), { stage: 0, mark: 'sft-hint' });
check('sft=0.849 → 不动作',
  mergeSft(0, 0, 0.849), { stage: 0, mark: '' });
check('sft=0.706（物业真实分数）→ 不动作',
  mergeSft(0, 0, 0.706), { stage: 0, mark: '' });

console.log('\n四、只抬不降（模型分低不能否决另外两票）');
check('sft=0.05 + 当前 S4 → 保持 S4',
  mergeSft(4, 3, 0.05), { stage: 4, mark: '' });
check('sft=0.95 + 当前 S4 → 保持 S4，不必再抬',
  mergeSft(4, 3, 0.95), { stage: 4, mark: '' });
check('sft=0.95 + 当前 S2 → 保持 S2',
  mergeSft(2, 1, 0.95), { stage: 2, mark: '' });

console.log('\n五、缺席与脏数据');
check('sft=null（未接入/超时）→ 不动作',
  mergeSft(0, 0, null), { stage: 0, mark: '' });
check('sft=undefined → 不动作',
  mergeSft(1, null, undefined), { stage: 1, mark: '' });
check('sft=NaN → 不动作',
  mergeSft(0, 0, NaN), { stage: 0, mark: '' });
check('sft=Infinity → 不动作（不是有效概率）',
  mergeSft(0, 0, Infinity), { stage: 0, mark: '' });

console.log(`\n结果：通过 ${pass} 条，失败 ${fail} 条\n`);
process.exit(fail ? 1 : 0);
