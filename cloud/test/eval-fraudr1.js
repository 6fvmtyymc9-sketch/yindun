'use strict';
/**
 * Fraud-R1 中文子集 → AntiFraud-SFT 文本模式：分类型漏报表
 * ============================================================================
 * 【这份数据能回答什么、不能回答什么，先说清楚】
 *
 * 能：模型对「五类骗术」的漏报率有没有类型倾斜。比如冒充公检法 100% 抓到、
 *     虚假招聘只有 40%，那就是真实的覆盖缺口，可以写进报告。
 *
 * 不能：算误报率。这份全量都是骗术样本，**没有一条正常文本**。
 *     而 SFT 眼下最疼的恰恰是误报（正常物业通知被判 0.71）。
 *     拿它算「准确率」是错的，本脚本因此只输出漏报，不输出准确率。
 *
 * 还有一个不能忽略的事实：这些文本是 **DeepSeek-R1 生成的**（字段
 * `generation model`）。所以：
 *   · 拿它评 DeepSeek 是循环论证 —— 等于让出题人做自己的卷子，本脚本不评；
 *   · 拿它评 SFT 文本模式 / 规则引擎是成立的 —— 这两个都没见过这批数据。
 *
 * 用法：
 *   node test/eval-fraudr1.js                 # 全量 1070 条
 *   node test/eval-fraudr1.js 200             # 只跑前 200 条（试水）
 *   FRAUDR1=/别的路径.json node test/eval-fraudr1.js
 */
const fs = require('fs');
const path = require('path');

require('../src/env');
const { Sft } = require('../src/Sft');

const SRC = process.env.FRAUDR1
  || 'D:/华为ict/外部数据集/fraudr1/FP-base-full/FP-base-Chinese.json';
const OUT = path.join(__dirname, 'out-fraudr1-sft.json');
const LIMIT = Number(process.argv[2] || 0);
const TH = Number(process.env.SFT_TEXT_TH || 0.5);

const CN = {
  phishing: '钓鱼/诈骗邮件',
  'network friendship': '网恋交友',
  'fake job posting': '虚假招聘',
  'fraudulent service': '虚假服务',
  impersonation: '冒充身份',
};

(async () => {
  const sft = new Sft();
  if (!sft.enabled) {
    console.error('[fraudr1] 没配 SFT_URL');
    process.exit(1);
  }
  const ping = await sft.ping();
  if (!ping.ok) {
    console.error('[fraudr1] 模型没就绪：', ping.msg || ping.error || '未知');
    process.exit(1);
  }

  let data = JSON.parse(fs.readFileSync(SRC, 'utf8'));
  if (LIMIT > 0) data = data.slice(0, LIMIT);
  console.log(`[fraudr1] ${path.basename(SRC)} 共 ${data.length} 条 · 文本模式 · 阈值 ${TH}`);
  console.log('[fraudr1] 全部是诈骗样本，只算漏报，不算准确率\n');

  const rows = [];
  for (let i = 0; i < data.length; i++) {
    const c = data[i];
    const text = String(c['generated text'] || c.raw_data || '').trim();
    const r = await sft.riskText(text);
    const row = {
      i, cat: c.category, sub: c.subcategory,
      chars: text.length,
      p: r ? Number(r.p.toFixed(4)) : null,
      hit: r ? r.p >= TH : null,
      ms: r ? r.ms : null,
    };
    rows.push(row);
    if (r) {
      process.stdout.write(`  ${String(i + 1).padStart(4)} ${CN[c.category] || c.category}`.padEnd(22)
        + ` p=${r.p.toFixed(3)} ${r.p >= TH ? '抓到' : '漏了'}  ${text.length}字 ${r.ms}ms\n`);
    } else {
      process.stdout.write(`  ${String(i + 1).padStart(4)} 调用失败\n`);
    }
  }

  fs.writeFileSync(OUT, JSON.stringify(rows, null, 1), 'utf8');

  // 按子类汇总 —— 单子类 150 条，这个粒度才有意义
  const by = new Map();
  for (const r of rows) {
    if (r.hit === null) continue;
    const k = r.sub;
    if (!by.has(k)) by.set(k, { n: 0, hit: 0 });
    const s = by.get(k);
    s.n++; if (r.hit) s.hit++;
  }
  console.log('\n按子类（漏报 = 判为正常）');
  console.log('  ' + '子类'.padEnd(58) + '漏报/总数');
  const lines = [...by.entries()].sort((a, b) => (a[1].hit / a[1].n) - (b[1].hit / b[1].n));
  for (const [k, s] of lines) {
    const miss = s.n - s.hit;
    console.log('  ' + String(k).slice(0, 56).padEnd(58)
      + `${String(miss).padStart(3)}/${s.n}  ${(miss / s.n * 100).toFixed(1)}%`);
  }
  const ok = rows.filter(r => r.hit !== null);
  const miss = ok.filter(r => !r.hit).length;
  console.log(`\n合计漏报 ${miss}/${ok.length}（${ok.length ? (miss / ok.length * 100).toFixed(1) : '-'}%）`
    + `  平均 ${Math.round(ok.reduce((s, r) => s + (r.ms || 0), 0) / (ok.length || 1))} ms/条`);
  if (rows.length > ok.length) console.log(`⚠️ ${rows.length - ok.length} 条调用失败，未计入`);
  console.log(`明细 → ${OUT}`);
})();
