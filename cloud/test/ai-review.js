'use strict';
/**
 * ============================================================================
 *  ai-review.js —— 大模型复核效果评测
 * ----------------------------------------------------------------------------
 *  用法：
 *      node test/ai-review.js                 # 评测留出集
 *      node test/ai-review.js cases.json      # 评测回归集
 *      node test/ai-review.js holdout.json --limit 5
 *
 *  【这个脚本回答什么问题】
 *    现在端侧只有"本地 30~45 分"的灰区才送大模型（SmsReceiver.aiReview）。
 *    留出集漏报的 12 条诈骗，几乎都是只命中"陌生号码"的 15 分短信——
 *    按现在的门限，它们连复核的门都进不去。
 *
 *    如果把范围扩成"本地未达 warn + 非服务号"，能救回多少？
 *    代价是误报涨多少？
 *
 *    这两个数出来之前不要改 SmsReceiver：凭感觉扩大范围，
 *    唯一的确定结果是 MaaS 账单变大。这是 2026-11-10 定下的规矩。
 *
 *  【为什么要用和手机端一字不差的 prompt】
 *    system prompt 是从 Pangu.java 的 reviewSms() 抄过来的。
 *    如果这里改得更"聪明"，测出来的就是脚本的本事，不是线上服务的效果。
 *    改 prompt 必须两边同时改。
 *
 *  【花钱】
 *    每条约 200 输入 + 40 输出 token，按 deepseek-v4.1-flash 时段价
 *    约 0.0006 元。整个留出集 35 条不到 3 分钱。
 * ============================================================================
 */
const path = require('path');
require('../src/env');

const { Judge, loadRules } = require('../src/Judge');
const { Pangu } = require('../src/Pangu');

const RANK = { none: 0, watch: 1, warn: 2, high: 3, block: 4 };

const rules = loadRules(path.join(__dirname, '..', 'config', 'rules.json'));
const judge = new Judge(rules);
const L = rules.levels || { watch: 25, warn: 45, high: 70, block: 90 };

const file = process.argv.find(a => a.endsWith('.json')) || 'holdout.json';
const limitIdx = process.argv.indexOf('--limit');
const LIMIT = limitIdx > 0 ? Number(process.argv[limitIdx + 1]) : 0;
const { cases } = require(path.resolve(__dirname, file));

// 与 Pangu.java reviewSms() 完全一致，改一边必须改两边
const SYS =
  '你是反诈短信审核员。判断给定短信是不是诈骗短信。\n' +
  '严格只输出一个 JSON，不要任何解释文字：\n' +
  '{"scam":true 或 false, "stage":"S0"到"S4", "reason":"不超过20字"}\n' +
  '判定标准：\n' +
  '- 冒充公检法、要求转账到安全账户、要求报验证码、要求别告诉家人 → 是诈骗\n' +
  '- 冒充客服退款、要求点链接验证身份、要求下载App → 是诈骗\n' +
  '- 银行/平台发来的正常验证码、账单、取件码 → 不是诈骗\n' +
  '- 促销、优惠券、活动通知 → 不是诈骗\n' +
  'stage 含义：S1套近乎 S2吓唬 S3让你别告诉家人 S4要钱。不是诈骗填 S0。';

/**
 * 新的复核门限：本地没到 warn、不是服务号，且（陌生号码 或 正文够长）。
 *
 * 【为什么排除服务号】银行、运营商、快递的正常短信全在这个区间，
 *   送模型纯属烧钱；模型的偶发误判还会把正常短信变成警报。
 * 【为什么"够长"也算】冒充亲友、投资理财这类骗局不一定用陌生号伪装，
 *   但一定啰嗦——短到 15 字以内的多是验证码、取件码、问候。
 */
function wouldReview(c, r) {
  if (c.who === 'service') return false;
  if ((RANK[r.level] || 0) >= RANK.warn) return false;
  if (c.who === 'stranger') return true;
  return (c.text || '').length >= 15;
}

/** 从模型回复里抠 JSON（与端侧 extractJson 同样的容错） */
function extractJson(s) {
  if (!s) return null;
  const t = String(s).replace(/```(?:json)?/gi, '');
  const i = t.indexOf('{');
  const j = t.lastIndexOf('}');
  if (i < 0 || j <= i) return null;
  try { return JSON.parse(t.slice(i, j + 1)); } catch (e) { return null; }
}

async function main() {
  const p = new Pangu({});
  const st = p.status ? p.status() : { ok: true };
  if (st && st.ok === false) {
    console.log('模型未配置：' + (st.msg || ''));
    process.exit(2);
  }
  console.log(`模型 ${p.model} · 样本文件 ${file} · 规则 v${rules.version}\n`);

  const picked = [];
  for (const c of cases) {
    const r = judge.scoreSms(c.text, c.who, !!c.code);
    if (wouldReview(c, r)) picked.push({ c, r });
  }

  const list = LIMIT > 0 ? picked.slice(0, LIMIT) : picked;
  console.log(`进入复核的样本 ${picked.length} 条` +
    (LIMIT > 0 ? `（本次只跑前 ${list.length} 条）` : '') + '\n');

  let fraudN = 0, fraudHit = 0, normalN = 0, normalBad = 0, err = 0;

  for (let i = 0; i < list.length; i += 4) {
    const batch = list.slice(i, i + 4);
    await Promise.all(batch.map(async ({ c, r }) => {
      const user = `发送方身份：${c.who === 'stranger' ? '陌生号码' : c.who === 'service' ? '服务号' : '未知'}` +
        `\n短信正文：${c.text.length > 200 ? c.text.slice(0, 200) : c.text}`;
      let scam = null, why = '';
      try {
        const out = await p.chat(
          [{ role: 'system', content: SYS }, { role: 'user', content: user }],
          { temperature: 0, maxTokens: 200 }
        );
        const j = extractJson(out);
        if (j && typeof j.scam === 'boolean') { scam = j.scam; why = j.reason || ''; }
        else { err++; why = '无法解析：' + String(out).slice(0, 40); }
      } catch (e) {
        err++; why = '请求失败：' + e.message;
      }

      const isFraudCase = c.expect === 'fraud';
      if (isFraudCase) { fraudN++; if (scam === true) fraudHit++; }
      else { normalN++; if (scam === true) normalBad++; }

      const mark = scam === null ? '?' : (isFraudCase ? (scam ? '✓' : '✗') : (scam ? '✗' : '✓'));
      console.log(`${mark} 本地${String(r.score).padStart(3)}分 ${isFraudCase ? '诈骗' : '正常'} ` +
        `[${c.type}] ${c.text.slice(0, 30)}  → ${scam === null ? '—' : scam ? 'AI判诈骗' : 'AI判正常'} ${why}`);
    }));
  }

  const pct = (a, b) => (b ? ((a / b) * 100).toFixed(1) : '0.0') + '%';
  console.log('\n结果：');
  console.log(`  诈骗样本 ${fraudN} 条，AI 抓回 ${fraudHit} 条（${pct(fraudHit, fraudN)}）`);
  console.log(`  正常样本 ${normalN} 条，AI 误判 ${normalBad} 条（${pct(normalBad, normalN)}）`);
  if (err) console.log(`  失败/无法解析 ${err} 条`);
}

main().catch(e => { console.error('ERR', e.message); process.exit(1); });
