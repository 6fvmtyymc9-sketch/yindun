'use strict';
/**
 * 词表 3.6 下重测微信 4 条分级 + ETC 短信分数
 * 用途：初赛填写稿第 160/161 行两个数字的实测替换值
 * 用法：node rescore-sms.js
 */
const path = require('path');
const { Judge, loadRules } = require('../src/Judge');

const rules = loadRules(path.join(__dirname, '..', 'config', 'rules.json'));
const judge = new Judge(rules);
console.log(`规则版本 ${rules.version || '?'} · 关键词 ${(rules.words || rules.keywords || []).length} · 反例 ${(rules.counter || rules.negs || []).length}`);

const cases = [
  // —— ETC 钓鱼短信（填写稿第 112/161 行原文）——
  { tag: 'ETC 原文', who: 'stranger', text: '您的ETC已停用，请点击 t.cn/Etc9 认证' },
  { tag: 'ETC 变体', who: 'stranger', text: '【ETC】您的通行卡已停用，请点 t.cn/Etc7 认证，逾期注销' },
  // —— 微信通知 4 条（四档分级验证）——
  { tag: '微信-1 陌生加好友', who: 'stranger', text: '你好，我是你女儿的朋友，她让我加你微信' },
  { tag: '微信-2 熟人要钱', who: 'stranger', text: '妈，我手机换了，这是新号，先转五千给我急用' },
  { tag: '微信-3 冒充客服', who: 'stranger', text: '您的包裹丢失，我们将三倍赔偿，请加客服微信办理退款' },
  { tag: '微信-4 刷单引流', who: 'stranger', text: '在家动动手指日赚300，点赞关注返佣，加微信详聊' },
  // —— 对照：正常微信 ——
  { tag: '微信-正常家人', who: 'contact', text: '妈，今晚回家吃饭吗？我买了鱼' },
];

for (const c of cases) {
  const r = judge.scoreSms(c.text, c.who, false);
  console.log(`${String(r.score).padStart(3)}  ${c.tag.padEnd(16)} level=${String(r.level).padEnd(6)} stage=${r.stage ?? '-'}  ${c.text}`);
}
