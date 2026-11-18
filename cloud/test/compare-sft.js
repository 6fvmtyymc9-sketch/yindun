'use strict';
/**
 * AntiFraud-SFT 文本模式基线
 * ============================================================================
 * 把 holdout.json 的每一条原样交给 SFT 的 /risk（text 模式），记下 p。
 * 产出的 JSON 与 out-llm.json / out-rules.json 字段对齐，方便并列看。
 *
 * 【为什么专门测文本模式】
 *   SFT 的本行是"听录音"。文本模式是它没有音频时的兜底路径，
 *   接口文档自己也写着"结论要打折看"。但我们一直在用"文字走 DeepSeek、
 *   录音走 SFT"来解释分工 —— 这句话此前只有推理，没有数字。
 *   跑完这一份，"专用模型判文字到底行不行"就有证据了。
 *
 * 【判 fraud 的阈值】默认 p >= 0.5，跟服务端 verdict 的定义一致。
 *   另外脚本会把阈值从 0.3 扫到 0.9，看漏报/误报怎么变 ——
 *   因为云端抬分用的 0.85 是给"音频+融合"定的，不能直接搬到文本上。
 *
 * 用法：node test/compare-sft.js              # 跑 holdout
 *       node test/compare-sft.js cases.json   # 换数据集
 *       SFT_TEXT_TH=0.7 node test/compare-sft.js
 */
const fs = require('fs');
const path = require('path');

require('../src/env');            // 项目自带的 .env 加载器，零依赖
const { Sft } = require('../src/Sft');

const SRC = process.argv[2] || 'holdout.json';
const OUT = path.join(__dirname, 'out-sft-text.json');
const TH = Number(process.env.SFT_TEXT_TH || 0.5);

(async () => {
  const sft = new Sft();
  if (!sft.enabled) {
    console.error('[compare-sft] 没配 SFT_URL，先把它填进 .env');
    process.exit(1);
  }
  const ping = await sft.ping();
  if (!ping.ok) {
    console.error('[compare-sft] 模型没就绪：', ping.msg || ping.error || '未知原因');
    process.exit(1);
  }

  const set = JSON.parse(fs.readFileSync(path.join(__dirname, SRC), 'utf8')).cases;
  console.log(`[compare-sft] ${SRC} 共 ${set.length} 条 · 文本模式 · 阈值 ${TH}`);

  const rows = [];
  for (let i = 0; i < set.length; i++) {
    const c = set[i];
    const r = await sft.riskText(c.text);
    if (!r) {
      rows.push({ i, expect: c.expect, type: c.type, text: c.text, error: '调用失败或超时' });
      console.log(`  ${String(i + 1).padStart(2)} 调用失败`);
      continue;
    }
    const isFraud = r.p >= TH;
    const truth = c.expect === 'fraud';
    const hit = isFraud === truth ? 'ok' : 'wrong';
    rows.push({
      i, expect: c.expect, type: c.type, text: c.text,
      p: Number(r.p.toFixed(4)), isFraud,
      reason: '',              // 这个模型只回概率，不给理由 —— 如实留空，不编
      ms: r.ms, hit,
    });
    console.log(`  ${String(i + 1).padStart(2)} ${truth ? '诈骗' : '正常'} → p=${r.p.toFixed(3)} ${isFraud ? '判诈骗' : '判正常'} ${hit === 'ok' ? '✓' : '✗'} ${r.ms}ms`);
  }

  fs.writeFileSync(OUT, JSON.stringify(rows, null, 1), 'utf8');

  const ok = rows.filter(r => typeof r.p === 'number');
  const fraud = ok.filter(r => r.expect === 'fraud');
  const normal = ok.filter(r => r.expect !== 'fraud');

  const stat = (th) => ({
    th,
    miss: fraud.filter(r => r.p < th).length,
    falarm: normal.filter(r => r.p >= th).length,
  });
  const main = stat(TH);
  const pct = (n, d) => d ? (n / d * 100).toFixed(1) + '%' : '-';

  console.log(`\n诈骗 ${fraud.length} 条，漏报 ${main.miss} 条（${pct(main.miss, fraud.length)}）`);
  console.log(`正常 ${normal.length} 条，误报 ${main.falarm} 条（${pct(main.falarm, normal.length)}）`);
  console.log(`平均耗时 ${Math.round(ok.reduce((s, r) => s + (r.ms || 0), 0) / (ok.length || 1))} ms/条`);
  if (rows.length > ok.length) console.log(`⚠️ ${rows.length - ok.length} 条调用失败，未计入统计`);

  // 阈值敏感性：说明 0.85 这个数搬到文本模式上意味着什么
  console.log('\n阈值扫描（漏报 / 误报）：');
  for (const th of [0.3, 0.5, 0.7, 0.85, 0.9]) {
    const s = stat(th);
    console.log(`  p>=${th.toFixed(2)}  漏报 ${s.miss}/${fraud.length}  误报 ${s.falarm}/${normal.length}`
      + (th === TH ? '   ← 本次用的' : ''));
  }
  console.log(`\n明细 → ${OUT}`);
})();
