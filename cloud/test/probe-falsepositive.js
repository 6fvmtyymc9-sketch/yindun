'use strict';
/**
 * 冒充亲属索款判据的误报压力测试
 * 目的：新判据只能拦"陌生号 + 直系亲属称谓 + 索款动作"，不能把下面这些打成阻断
 * 用法：node test/probe-falsepositive.js
 */
const path = require('path');
const { Judge, loadRules } = require('../src/Judge');

const judge = new Judge(loadRules(path.join(__dirname, '..', 'config', 'rules.json')));

const mustPass = [ // 必须放行（最多 watch）
  ['银行到账-母亲', 'service', '【工商银行】您母亲账户转入5000元，余额12,340.00元'],
  ['银行到账-父亲', 'service', '【建设银行】您父亲尾号8842账户存入20000元'],
  ['银行支出', 'service', '【农业银行】您尾号3321账户支出人民币300.00元'],
  ['工资入账', 'service', '【招商银行】您账户收到工资12,800元，请注意查收'],
  ['退款到账', 'service', '【支付宝】您的退款199元已到账，请查收'],
  ['贷款-大哥', 'stranger', '大哥您好，资金周转需要钱的话可以找我，当天放款'],
  ['快递', 'stranger', '您的快递已放至丰巢，取件码 8821'],
  ['外卖', 'stranger', '外卖到了，放门口了，20元已经付过了'],
  ['社区通知', 'stranger', '【社区】本周六上午免费理发，欢迎参加'],
  ['家人借钱-通讯录', 'contact', '妈，借我两千块周转一下，月底还你'],
  ['家人转钱-通讯录', 'contact', '妈，我给你转了五千，你查一下'],
];

const mustCatch = [ // 必须抓到（>= warn）
  ['冒充子女-转账', 'stranger', '妈，我手机换了，这是新号，先转五千给我急用'],
  ['冒充子女-打钱', 'stranger', '妈，先给我打点钱，五千就行，回头还你'],
  ['冒充子女-卡号', 'stranger', '妈，我微信限额了，你转五千到这个卡上'],
  ['冒充子女-借钱', 'stranger', '妈，借我两千块周转一下，月底还你'],
  ['冒充子女-保释金', 'stranger', '妈，我被派出所扣了，需要交保释金，你快转两万到这个账户'],
  ['冒充子女-应急', 'stranger', '爸，我手机丢了这是新号，先给我转一万应急'],
  ['冒充子女-要钱', 'stranger', '妈，我在外面出了点事，需要钱，你打五万到这个卡号'],
  ['冒充子女-保密', 'stranger', '妈，我手机换了，这是新号，先转五千给我急用，别告诉爸'],
];

const RANK = { none: 0, watch: 1, warn: 2, high: 3, block: 4 };

/** 已知既有误报：词表 3.6 老问题，与本次新判据无关，见文末说明 */
const knownFalsePositive = [
  ['推销-阿姨', 'stranger', '阿姨您好，急需用钱可以联系我们，额度高放款快'],
  ['推销-叔叔', 'stranger', '叔叔，我们有养老金理财，年化5%，需要钱生钱吗'],
];

let bad = 0;

console.log('=== 必须放行（判到 warn 及以上即为误报）===');
for (const [tag, who, text] of mustPass) {
  const r = judge.scoreSms(text, who, false);
  const ok = RANK[r.level] <= RANK.watch;
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${String(r.score).padStart(3)} ${r.level.padEnd(6)} ${tag}`);
}

console.log('\n=== 必须抓到（判到 watch 及以下即为漏报）===');
for (const [tag, who, text] of mustCatch) {
  const r = judge.scoreSms(text, who, false);
  const ok = RANK[r.level] >= RANK.warn;
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${String(r.score).padStart(3)} ${r.level.padEnd(6)} ${tag}`);
}

console.log(`\n${bad === 0 ? '全部通过' : `有 ${bad} 条不符合预期`}`);

// ============================================================================
//  已知既有误报（**不是本次新判据引入的**，是词表 3.6 的老问题）
//  单列出来是为了不掩盖：这两条确实会被推给子女，但根因在「放款快/急需/年化」这些正向词
//  权重偏高，与"陌生号+亲属称谓+索款动作"那条判据无关。修它们要动词表权重，
//  会牵动全部既有数字与答辩口径，另立项处理。
// ============================================================================
console.log('\n=== 已知既有误报（不计入本次通过标准）===');
for (const [tag, who, text] of knownFalsePositive) {
  const r = judge.scoreSms(text, who, false);
  console.log(`KNOWN ${String(r.score).padStart(3)} ${r.level.padEnd(6)} ${tag}  ← ${r.why}`);
}

process.exit(bad === 0 ? 0 : 1);
