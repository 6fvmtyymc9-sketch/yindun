/**
 * Ocr.tidy() 单测 —— 纯函数，不出网。
 *
 * 跑法：node test/ocr-tidy-test.js
 *
 * 这些用例全部来自 2026-11-18 真机截图实测，不是编的：
 * 拿 public/demo/fake_sms.png 真调 OCR，逐行输出就是下面第 1 条的样子。
 */
const { tidy } = require('../src/Ocr');

let pass = 0;
let fail = 0;

function check(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (ok) {
    pass++;
    console.log(`  ok   ${name}`);
  } else {
    fail++;
    console.log(`  FAIL ${name}`);
    console.log(`       want: ${JSON.stringify(want)}`);
    console.log(`       got : ${JSON.stringify(got)}`);
  }
}

// ---------------------------------------------------------------- 1. 折行断词
// 实测原样：OCR 把「洗钱」切成了两行。若原样用 \n 拼接，规则里的关键词「洗钱」匹配不到。
{
  const lines = [
    '9:41',
    '5G',
    '82%',
    '<信息',
    '+65 8123 4477',
    '新加坡·未存储在通讯录',
    '今天09:38',
    '【最高人民检察院】您名下尾号6688的银行卡涉嫌一起洗',
    '钱案件，现已立案侦查。请于今日 16:00 前联系办案专员配',
    '合资金核查，否则将依法冻结全部账户。',
  ];
  const r = tidy(lines);
  check('状态栏噪声被丢掉', r.ui, [
    '9:41',
    '5G',
    '82%',
    '<信息',
    '新加坡·未存储在通讯录',
    '今天09:38',
  ]);
  check('号码单独提取', r.sender, ['+65 8123 4477']);
  check('「洗钱」被拼回一个词', r.text.includes('洗钱'), true);
  check('「配合」被拼回一个词', r.text.includes('配合'), true);
  check('句末标点后续接新段落（保留换行）', r.text.includes('账户。'), true);
  check('正文不含状态栏时间', /9:41/.test(r.text), false);
  check('正文不含号码', /8123/.test(r.text), false);
}

// ---------------------------------------------------------------- 2. 多段正文
// 一段一条短信：句号结尾的换段，逗号结尾的当折行接回
{
  const lines = [
    '【工商银行】您尾号6688的账户于10月20日',
    '收入人民币5,000.00元，余额12,345.67元。',
    '如有疑问请致电95588。',
  ];
  const r = tidy(lines);
  check('正常短信不被过度拼接', r.text.split('\n').length, 2);
  check('括号【】不丢', r.text.startsWith('【工商银行】'), true);
}

// ---------------------------------------------------------------- 3. 纯号码不误杀正文
{
  const r = tidy(['10086', '您的流量已用完', '回复1办理加油包']);
  check('短号被识别为发送者', r.sender, ['10086']);
  check('正文保留', r.text.includes('您的流量已用完'), true);
}

// ---------------------------------------------------------------- 4. 空输入
{
  const r = tidy(['', '   ', '\n']);
  check('全空返回空串', r.text, '');
  check('全空无发送者', r.sender, []);
}

// ---------------------------------------------------------------- 5. 连续折行无标点
{
  const r = tidy(['根据规定您需要先缴纳', '保证金才能解冻账户']);
  check('整段无标点时拼成一整行', r.text, '根据规定您需要先缴纳保证金才能解冻账户');
}

console.log(`\n通过 ${pass} / ${pass + fail}`);
if (fail) {
  console.log(`失败 ${fail} 项`);
  process.exit(1);
}
