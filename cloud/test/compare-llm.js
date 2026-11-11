'use strict';
/**
 * DeepSeek 留出集基线
 * ============================================================================
 * 这一份只干一件事：把 cloud/test/holdout.json 的每一条，原样交给 DeepSeek，
 * 问它"这是不是诈骗短信"，记下答案。不掺任何本地规则的分数提示。
 *
 * 为什么不用 Pangu.judgeSms：
 *   那个 prompt 会把"本地规则初判 30~45 分，属于拿不准"一起喂进去 ——
 *   等于提前告诉模型"这条可疑"。拿它跟规则对比，规则等于作弊。
 *   这里用中立提问，三路（规则 / TinyBERT / DeepSeek）看到的是同一句话。
 *
 * 用法：node test/compare-llm.js            # 跑 holdout，写 out-llm.json
 *       node test/compare-llm.js cases.json # 换数据集
 */
const fs = require('fs');
const path = require('path');

require('../src/env');            // 项目自带的 .env 加载器，零依赖

const SRC = process.argv[2] || 'holdout.json';
const OUT = path.join(__dirname, 'out-llm.json');

const SYS = [
  '你是反电信诈骗短信判别器，服务中国老年用户。',
  '判断这条短信是不是诈骗短信。',
  '只输出一行 JSON，不要 markdown 代码块，不要解释。格式：',
  '{"is_fraud":true,"type":"诈骗类型，不超过8字","reason":"不超过20字"}',
].join('\n');

async function ask(text) {
  const url = process.env.MAAS_URL;
  const key = process.env.MAAS_KEY;
  const model = process.env.MAAS_MODEL || 'deepseek-v4.1-flash';
  if (!url || !key) throw new Error('缺 MAAS_URL / MAAS_KEY');

  const t0 = Date.now();
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
    body: JSON.stringify({
      model,
      messages: [
        { role: 'system', content: SYS },
        { role: 'user', content: '短信正文：' + text },
      ],
      max_tokens: 200,
      temperature: 0.1,
    }),
  });
  const ms = Date.now() - t0;
  if (!r.ok) throw new Error(`HTTP ${r.status} ${(await r.text()).slice(0, 200)}`);
  const data = await r.json();
  const raw = data.choices?.[0]?.message?.content || '';
  const m = String(raw).match(/\{[\s\S]*\}/);
  let verdict = null;
  if (m) {
    try { verdict = JSON.parse(m[0]); } catch (e) { /* 交给下面记 null */ }
  }
  return {
    isFraud: verdict ? !!verdict.is_fraud : null,
    type: verdict ? String(verdict.type || '').slice(0, 20) : '',
    reason: verdict ? String(verdict.reason || '').slice(0, 60) : '',
    ms,
    raw: m ? undefined : String(raw).slice(0, 120),
  };
}

(async () => {
  const set = JSON.parse(fs.readFileSync(path.join(__dirname, SRC), 'utf8')).cases;
  const rows = [];
  console.log(`[compare-llm] ${SRC} 共 ${set.length} 条，模型 ${process.env.MAAS_MODEL}`);

  for (let i = 0; i < set.length; i++) {
    const c = set[i];
    try {
      const r = await ask(c.text);
      const truth = c.expect === 'fraud';
      const hit = r.isFraud === null ? 'parse-fail' : (r.isFraud === truth ? 'ok' : 'wrong');
      rows.push({ i, expect: c.expect, type: c.type, text: c.text, ...r, hit });
      console.log(`  ${String(i + 1).padStart(2)} ${truth ? '诈骗' : '正常'} → ${r.isFraud === null ? '解析失败' : (r.isFraud ? '判诈骗' : '判正常')} ${hit === 'ok' ? '✓' : '✗'} ${r.ms}ms  ${r.reason || r.raw || ''}`);
    } catch (e) {
      rows.push({ i, expect: c.expect, type: c.type, text: c.text, error: e.message });
      console.log(`  ${i + 1} 调用失败：${e.message}`);
    }
  }

  fs.writeFileSync(OUT, JSON.stringify(rows, null, 1), 'utf8');

  const fraud = rows.filter(r => r.expect === 'fraud');
  const normal = rows.filter(r => r.expect !== 'fraud');
  const miss = fraud.filter(r => r.isFraud !== true).length;      // 诈骗没判出来 = 漏报
  const falarm = normal.filter(r => r.isFraud !== false).length;  // 正常判成诈骗 = 误报
  const avg = Math.round(rows.reduce((s, r) => s + (r.ms || 0), 0) / rows.length);
  console.log(`\n诈骗 ${fraud.length} 条，漏报 ${miss} 条（${(miss / fraud.length * 100).toFixed(1)}%）`);
  console.log(`正常 ${normal.length} 条，误报 ${falarm} 条（${(falarm / normal.length * 100).toFixed(1)}%）`);
  console.log(`平均耗时 ${avg} ms/条`);
  console.log(`明细 → ${OUT}`);
})();
