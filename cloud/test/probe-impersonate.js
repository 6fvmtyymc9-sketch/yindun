'use strict';
/**
 * 冒充子女要钱的规则覆盖缺口探查
 * 目的：看"没出现『转账/汇款』字样"的口语化索款能不能被判到 warn 以上
 */
const path = require('path');
const { Judge, loadRules } = require('../src/Judge');

const judge = new Judge(loadRules(path.join(__dirname, '..', 'config', 'rules.json')));

const cases = [
  '妈，我手机换了，这是新号，先转五千给我急用',
  '妈，我手机掉了，这是新号码，你加一下我微信，有急事',
  '妈，我出事了，急需用钱，你先给我转五千',
  '妈，先给我打点钱，五千就行，回头还你',
  '妈，我微信限额了，你转五千到这个卡上',
  '妈，借我两千块周转一下，月底还你',
  '爸，我手机丢了这是新号，先给我转一万应急',
  '妈，我在外面出了点事，需要钱，你打五万到这个卡号',
  '妈，我手机换了，这是新号，先转五千给我急用，别告诉爸',
  '妈，我被派出所扣了，需要交保释金，你快转两万到这个账户',
];

for (const t of cases) {
  const r = judge.scoreSms(t, 'stranger', false);
  const mark = (r.level === 'warn' || r.level === 'high' || r.level === 'block') ? 'OK ' : '>> ';
  console.log(`${mark}${String(r.score).padStart(3)}  ${String(r.level).padEnd(6)} ${t}`);
}
