'use strict';
/**
 * ============================================================================
 *  CallAudio.js —— 通话录音复盘
 * ----------------------------------------------------------------------------
 *  POST /api/callaudio
 *    手机在陌生来电挂断后，找到系统自带的通话录音，原样传上来：
 *    { elderId, number, durSec, name, audio: base64, demoText? }
 *
 *  处理流程：
 *    录音 → 转文字（Asr）→ 两路判定 → 落库 + 推子女端 → 结论回给手机
 *
 *  两路判定怎么合：
 *    · 大模型（Pangu.judgeCall）定阶段：转写稿很长、口语、没有说话人，
 *      逐词打分会被"我不会告诉你验证码"这种话误伤，语义判断更准。
 *    · 规则引擎（Judge.scoreSms）给可解释的命中词，模型挂了就靠它兜底。
 *    · 一条保险：规则看到了"要钱"(S4)，模型却说不是诈骗 —— 不直接放过，
 *      降为 S2 提醒子女核实一下。两边谁都不能单方面把警报清零。
 *
 *  这是挂断之后的复盘，不参与通话中的实时拦截（那一层仍是本地规则、不读内容）。
 *
 *  隐私：
 *    · 音频只在内存里处理，不落盘、不入库
 *    · 转写全文不入库，只存模型摘出来的几句证据
 *    · 配了 YD_TOKEN 时，请求头必须带 X-Yd-Token
 * ============================================================================
 */

const express = require('express');
const { Asr } = require('./Asr');

const MAX_AUDIO = 25 * 1024 * 1024;     // 25MB，足够 2 小时的 AMR 或 40 分钟的 mp3
const STAGE_NAMES = { 0: '无风险', 1: '套近乎', 2: '吓唬', 3: '隔离', 4: '要钱' };
const LEVEL_OF_STAGE = { 0: 'none', 1: 'watch', 2: 'warn', 3: 'high', 4: 'block' };
const SOURCE_TXT = { sis: '华为云语音识别', openai: '语音识别', demo: '演示文本' };

/**
 * @param {import('express').Express} app
 * @param {{getJudge:Function, pangu:object, db:object, pushTo:Function, asr?:Asr}} deps
 */
function registerCallAudio(app, deps) {
  const { getJudge, pangu, db, pushTo } = deps;
  const asr = deps.asr || new Asr();
  const token = process.env.YD_TOKEN || '';
  if (!token) console.warn('[CallAudio] 未配置 YD_TOKEN，/api/callaudio 不校验身份（仅限演示）');
  console.log('[CallAudio] 转写方式：', asr.mode, '｜大模型：', pangu.enabled ? pangu.model : '未配置（只用规则）');

  const checkToken = (req, res, next) => {
    if (!token || req.get('X-Yd-Token') === token) return next();
    res.status(401).json({ ok: false, msg: '缺少或错误的 X-Yd-Token' });
  };

  // 必须在全局 express.json（12mb）之前注册，自己的 body 上限才生效
  const bigJson = express.json({ limit: '40mb' });

  app.get('/api/callaudio/ping', checkToken, (req, res) => {
    res.json({ ok: true, asr: asr.mode, llm: pangu.enabled ? pangu.model : null, tokenRequired: !!token });
  });

  app.post('/api/callaudio', checkToken, bigJson, async (req, res) => {
    const b = req.body || {};
    // 认证过的老人端用自己的编号；未认证的只能用公开演示编号 demo
    const elderId = (req.yd && req.yd.elderId) || 'demo';
    if (!(req.yd && req.yd.elderId) && b.elderId && b.elderId !== 'demo') {
      return res.status(401).json({ ok: false, msg: '需要老人端身份' });
    }
    const number = String(b.number || '').trim().slice(0, 32);
    const durSec = Math.max(0, Number(b.durSec) || 0);
    const name = String(b.name || 'call.mp3').slice(0, 120);
    const demoText = typeof b.demoText === 'string' ? b.demoText.slice(0, 8000) : '';

    let audio;
    try {
      audio = Buffer.from(String(b.audio || ''), 'base64');
    } catch (e) {
      return res.status(400).json({ ok: false, msg: 'audio 不是合法的 base64' });
    }
    if (!audio.length) return res.status(400).json({ ok: false, msg: '缺少录音 audio' });
    if (audio.length > MAX_AUDIO) return res.status(413).json({ ok: false, msg: '录音超过 25MB' });

    const t0 = Date.now();

    // ---------- 1. 转文字 ----------
    let asrOut;
    try {
      asrOut = await asr.transcribe({ audio, name, demoText });
    } catch (e) {
      console.warn('[CallAudio] 转写失败：', e.message);
      return res.status(e.status === 503 ? 503 : 502).json({ ok: false, msg: '转写失败：' + e.message });
    }
    const transcript = asrOut.text;
    const asrMs = Date.now() - t0;

    // ---------- 2. 两路判定 ----------
    // who 传空：已经确认是陌生号码才会传上来，不再额外 +15，免得长对话分数虚高
    const local = getJudge().scoreSms(transcript, '', false);
    const localStage = Number(String(local.stage).replace('S', '')) || 0;
    const ai = await pangu.judgeCall(transcript);

    let stage;
    let basis;
    if (ai) {
      stage = ai.stage;
      basis = 'ai';
      if (localStage >= 4 && stage < 2) {
        stage = 2;
        basis = 'ai+rule-floor';
      }
    } else {
      stage = localStage;
      basis = 'rule';
    }
    const level = LEVEL_OF_STAGE[stage];
    const isFraud = stage >= 2;

    const localWords = (local.hits || []).filter(h => h.weight > 0).map(h => h.word).slice(0, 4);
    const result = {
      stage,
      stageName: STAGE_NAMES[stage],
      level,
      isFraud,
      type: clean((ai && ai.type) || (isFraud ? '规则命中' : '')),
      reason: clean((ai && ai.reason) || (localWords.length ? '命中：' + localWords.join('、') : '没有明显可疑话术')),
      advice: clean((ai && ai.advice) || (isFraud ? '先别转钱，打电话问问家里人' : '看起来没问题')),
      evidence: ((ai && ai.evidence && ai.evidence.length)
        ? ai.evidence.map(e => (e.length < 8 ? quotesOf(transcript, [e])[0] : e))   // 只给了半句，补成整句
        : quotesOf(transcript, localWords)).map(clean),
      basis,
    };

    // ---------- 3. 落库 + 推子女端（warn 及以上） ----------
    let alert = null;
    if (rank(level) >= rank('warn')) {
      const quotes = result.evidence.map(s => `「${s}」`).join(' ');
      alert = {
        elderId,
        level,
        title: `妈妈接听的陌生来电疑似诈骗${result.type ? '（' + result.type + '）' : ''}`.slice(0, 120),
        body: [`号码 ${clean(number) || '未知'}，通话 ${fmtDur(durSec)}。`, result.reason, quotes].filter(Boolean).join('\n').slice(0, 500),
        why: `通话录音复盘 · S${stage}${result.stageName} · 转写来源：${SOURCE_TXT[asrOut.source] || asrOut.source}`
          + (basis === 'rule' ? ' · 仅规则判定' : ''),
        channel: 'callaudio',
      };
      try {
        alert.id = await db.addAlert(alert);
        const d = new Date();
        pushTo(elderId, {
          type: 'alert',
          alertId: alert.id,
          level: alert.level,
          title: alert.title,
          body: alert.body,
          why: alert.why,
          channel: alert.channel,
          summary: null,
          time: String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'),
        });
      } catch (e) {
        console.warn('[CallAudio] 推预警失败：', e.message);
      }
    }

    console.log(`[CallAudio] ${number || '未知号码'} ${fmtDur(durSec)} → S${stage}/${level}（${basis}，转写 ${asrOut.source} ${asrMs}ms，共 ${Date.now() - t0}ms）`);

    res.json({
      ok: true,
      ...result,
      pushed: !!alert,
      local: { score: local.score, stage: local.stage, why: local.why },
      asr: { source: asrOut.source, chars: transcript.length, ms: asrMs },
      meta: { ms: Date.now() - t0, model: ai ? pangu.model : null, cost: ai ? pangu.lastCost : 0 },
    });
  });
}

/**
 * 模型没给出可信的原话时，从转写稿里截出含命中词的整句当证据。
 * 老人端写的是"对方原话"，只能放真句子，不能放关键词。
 */
function quotesOf(text, words) {
  const sents = String(text || '').split(/[。！？!?；;\n]/).map(x => x.trim()).filter(Boolean);
  const out = [];
  for (const w of words) {
    const hit = sents.find(x => x.includes(w) && !out.includes(x));
    if (!hit) continue;
    // 太长就以命中词为中心截 28 个字
    if (hit.length <= 30) out.push(hit);
    else {
      const at = Math.max(0, hit.indexOf(w) - 10);
      out.push(hit.slice(at, at + 28));
    }
    if (out.length >= 3) break;
  }
  return out.length ? out : words;
}

/** 子女端网页用 innerHTML 渲染卡片，而转写内容是骗子说的话 —— 推出去前去掉 HTML 特殊字符 */
function clean(s) {
  return String(s == null ? '' : s).replace(/[<>&"'`]/g, '');
}

function rank(level) {
  return { none: 0, watch: 1, warn: 2, high: 3, block: 4 }[level] || 0;
}

function fmtDur(sec) {
  if (!sec) return '时长未知';
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m ? `${m} 分 ${s} 秒` : `${s} 秒`;
}

module.exports = { registerCallAudio };
