'use strict';
/**
 * test/ocr-ping.js —— OCR 自检：拿一张真实截图走一遍完整链路
 *
 * 为什么要有它：
 *   OCR 是"错误码很抽象、原因很具体"的那类接口 —— 401 可能是 AK/SK 不对，
 *   也可能只是服务器时间差了 20 分钟；403 八成是服务没开通。
 *   只看 /api/report 的返回，分不清是哪一种。这个脚本把原始报错打全，
 *   一眼能看出该改配置还是该去控制台开通。
 *
 * 用法：
 *   node test/ocr-ping.js                        # 只看配置，不发请求
 *   node test/ocr-ping.js ../public/demo/fake_sms.png
 *   node test/ocr-ping.js D:/path/to/shot.jpg
 *
 * 注意：每次带图片跑都会消耗一次 OCR 调用额度（每月 1000 次免费），
 * 不要写进任何循环或定时任务里。
 */

require('../src/env');
const fs = require('fs');
const path = require('path');
const { Ocr } = require('../src/Ocr');

const MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', bmp: 'image/bmp' };

(async () => {
  const ocr = new Ocr();
  console.log('=== OCR 配置 ===');
  console.log('  启用      :', ocr.enabled ? '是' : '否（缺 HW_AK / HW_SK）');
  console.log('  区域      :', ocr.region);
  console.log('  接入点    :', ocr.host);
  console.log('  路径      :', ocr.path);
  console.log('  超时      :', ocr.timeout + 'ms');
  console.log('  大小上限  :', (ocr.maxB64 / 1048576).toFixed(1) + 'MB (base64)');

  const file = process.argv[2];
  if (!file) {
    console.log('\n只检查了配置，没有发请求。要真跑一张图：');
    console.log('  node test/ocr-ping.js ../public/demo/fake_sms.png');
    return;
  }
  if (!ocr.enabled) {
    console.error('\n[停止] 没有 AK/SK，发出去也只会 401。先在 .env 里配 HW_AK / HW_SK（和 SIS 同一组）。');
    process.exitCode = 1;
    return;
  }

  const abs = path.resolve(process.cwd(), file);
  if (!fs.existsSync(abs)) {
    console.error('\n[停止] 文件不存在：' + abs);
    process.exitCode = 1;
    return;
  }
  const buf = fs.readFileSync(abs);
  const ext = (path.extname(abs).slice(1) || 'png').toLowerCase();
  console.log('\n=== 识别 ===');
  console.log('  文件      :', abs, `(${(buf.length / 1024).toFixed(0)}KB, ${MIME[ext] || '未知类型'})`);

  const t0 = Date.now();
  try {
    const out = await ocr.recognize({ imageBase64: buf.toString('base64'), mime: MIME[ext] || 'image/png' });
    const ms = Date.now() - t0;
    console.log('  耗时      :', ms + 'ms（接口自报 ' + out.ms + 'ms）');
    console.log('  文字块数  :', out.blocks);
    console.log('  字符数    :', out.chars);
    console.log('\n--- 识别结果 ---');
    console.log(out.text || '（没有识别到任何文字）');
    console.log('--- 结束 ---');
    if (!out.text) {
      console.log('\n提示：图里没字，或者图太小/太糊。短信截图请保证字占画面 1/10 以上。');
    }
  } catch (e) {
    console.error('  失败      :', e.message);
    if (e.code) console.error('  错误类型  :', e.code);
    console.error('\n按错误类型看这里：');
    console.error('  401 / APIGW.0301 → AK/SK 不对，或本机时间偏差超过 15 分钟');
    console.error('  403 / CBC.*      → 控制台没开通「文字识别 OCR」，或区域选错');
    console.error('  OCR_TOO_LARGE    → 图片太大，App 侧应压到 1600px 再传');
    console.error('  OCR_NETWORK      → 网络不通（ECS 上要确认能出公网）');
    process.exitCode = 1;
  }
})();
