'use strict';
/**
 * SIS 一句话识别自检 —— 不启动 HTTP 服务，直接用 Asr 类打真接口。
 *
 * 为什么要单独一个脚本：
 *   接真账号时，失败原因可能有三层（账号密码错 / 服务没开通 / 项目区域不对），
 *   走 /api/callaudio 会被其他环节的错误盖住。这里把它拆成两步分别报：
 *     ① IAM 换 token  —— 验账号密码与域名
 *     ② short-audio    —— 验服务开通与项目 ID
 *
 * 跑法：cd cloud && node test/sis-ping.js
 */

const fs = require('fs');
const path = require('path');
require('../src/env');
const { Asr } = require('../src/Asr');

const CALLS = path.join(__dirname, '../../demo/calls');

(async () => {
  const asr = new Asr();
  const s = asr.sis;

  console.log('配置：', {
    region: s.region,
    projectId: s.projectId ? `${s.projectId.slice(0, 8)}…（${s.projectId.length} 位）` : '(空)',
    auth: asr.sisAuth,
    ak: s.ak ? `${s.ak.slice(0, 6)}…` : '(空)',
    sk: s.sk ? '已填' : '(空)',
    iamUser: s.user || '(空)',
    iamDomain: s.domain || '(空)',
    password: s.pass ? '已填' : '(空)',
    property: s.property,
  });
  console.log('模式：', asr.mode);

  if (!asr.sisEnabled) {
    console.error('\n✗ SIS 没配全：需要 SIS_PROJECT_ID + 任一种凭证');
    console.error('   · AK/SK（推荐）：HW_AK / HW_SK');
    console.error('   · 现成 token：SIS_TOKEN');
    console.error('   · 账号密码：HW_IAM_USER / HW_IAM_PASS / HW_IAM_DOMAIN');
    process.exit(1);
  }

  // ---------- ① 凭证 ----------
  if (asr.sisAuth === 'aksk') {
    console.log('\n① 用 AK/SK 签名，跳过 IAM 换 token');
  } else {
    let t0 = Date.now();
    try {
      const tk = await asr.sisToken();
      console.log(`\n① IAM 换 token 成功（${Date.now() - t0} ms，token ${tk.length} 字符）`);
    } catch (e) {
      console.error('\n✗ ① IAM 换 token 失败：', e.message);
      console.error('   → 账号、密码、域名三者之一不对。域名必须是「账号名」，不是 IAM 用户名。');
      process.exit(2);
    }
  }

  // ---------- ② 真音频识别 ----------
  const files = fs.readdirSync(CALLS)
    .filter((f) => /\.(mp3|wav|m4a|amr|aac)$/i.test(f))
    .sort();
  if (!files.length) {
    console.error('\n✗ demo/calls 里没有音频文件');
    process.exit(3);
  }

  let ok = 0;
  let fail = 0;
  for (const f of files) {
    const audio = fs.readFileSync(path.join(CALLS, f));
    const txtPath = path.join(CALLS, f.replace(/\.[^.]+$/, '.txt'));
    const want = fs.existsSync(txtPath) ? fs.readFileSync(txtPath, 'utf8').trim() : '(无原稿)';

    const t1 = Date.now();
    try {
      const { text } = await asr.transcribe({ audio, name: f });
      ok++;
      console.log(`\n② ${f}  ${(audio.length / 1024).toFixed(0)} KB  ${Date.now() - t1} ms`);
      console.log('   SIS 识别：', text);
      console.log('   演示原稿：', want.slice(0, 240));
    } catch (e) {
      fail++;
      console.error(`\n② ${f} 失败：${e.message}`);
    }
  }

  console.log(`\n结果：成功 ${ok} 条，失败 ${fail} 条`);
  if (fail && !ok) {
    console.error('若报 not subscribe this service → 服务没开通，或项目 ID 不是 cn-north-4 的');
    console.error('若报 400 且提到 audio_format → 音频格式不被一句话识别支持，需要转码');
  }
  process.exit(fail ? 4 : 0);
})();
