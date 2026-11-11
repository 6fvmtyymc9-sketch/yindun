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
  /** 第三票：反诈专用模型（听音频）。没配 SFT_URL 就是 null，链路照旧走两路。 */
  const sft = deps.sft || null;
  /** 第三票最多等多久。手机端读超时是 90 秒（CallRecord.java 第 271 行），这里留余量。 */
  const sftWaitMs = Math.max(0, Number(process.env.SFT_WAIT_MS || 60000));
  const token = process.env.YD_TOKEN || '';
  if (!token) console.warn('[CallAudio] 未配置 YD_TOKEN，/api/callaudio 不校验身份（仅限演示）');
  console.log('[CallAudio] 转写方式：', asr.mode,
    '｜大模型：', pangu.enabled ? pangu.model : '未配置（只用规则）',
    '｜专用模型：', (sft && sft.enabled) ? `${sft.model}（最多等 ${Math.round(sftWaitMs / 1000)} 秒）` : '未接入');

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

    // ---------- 0. 第三票先发出去（它最慢，和转写并行跑） ----------
    //
    // 【为什么要并行】专用模型在本机 CPU 上一次前向要 40~80 秒，而手机端读超时
    //   只有 90 秒。串行等它会把一次正常的复盘拖成超时，所以一拿到录音就发起，
    //   转写和两路判定同时进行，总耗时取决于最慢的那一个。
    //
    // 【为什么有硬上限】模型没加载完、机器被别的活占住，都可能让它更久。
    //   到点就按"缺席"处理 —— 第三票是加分项，不能因为它把整条复盘搞失败。
    let sftTask = null;
    if (sft && sft.enabled && sftWaitMs > 0) {
      sftTask = sft.riskAudio({ audio, name }).catch(() => null);
    }

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

    // ---------- 2.5 第三票：专用模型听音频 ----------
    //
    // 【融合规矩：只抬不降】这条不是随便定的。
    //   · 它能抬：p≥0.85 说明模型从语气、语速、背景音里听出了转写稿丢掉的东西，
    //     这时哪怕规则和大模型都说没事，也该让子女知道一声。
    //   · 它不能单独降：模型只听到几十秒，没听到要钱那一段就会给低分。
    //     让它拿低分否决规则 + 大模型的一致结论，等于给骗子留了个后门。
    let sftOut = null;
    if (sftTask) {
      sftOut = await Promise.race([
        sftTask,
        new Promise(r => setTimeout(() => r(null), sftWaitMs)),
      ]);
    }
    if (sftOut && typeof sftOut.p === 'number' && sftOut.p >= 0.85 && stage < 2) {
      stage = 2;
      basis += '+sft';
    }

    const level = LEVEL_OF_STAGE[stage];
    const isFraud = stage >= 2;
    const sftState = (!sft || !sft.enabled) ? '未接入'
      : (!sftTask ? '已关闭' : (sftOut ? '已判定' : '超时或失败'));

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
      // 转写的真实情况：长录音是被切成几段识别的，如实告诉子女端
      asr: {
        source: asrOut.source,
        parts: asrOut.parts || 1,
        durationSec: Math.round(asrOut.durationSec || 0),
        ms: asrMs,
      },
      // 第三票的原始结论，如实给出去；没接入 / 超时就写 null，不编造
      sft: sftOut ? { p: Number(sftOut.p.toFixed(3)), verdict: sftOut.verdict || '', ms: sftOut.ms || 0 } : null,
      sftState,
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
          + ((asrOut.parts || 1) > 1 ? `（${Math.round(asrOut.durationSec)} 秒录音分 ${asrOut.parts} 段识别）` : '')
          + (basis.startsWith('rule') ? ' · 仅规则判定' : '')
          + (sftOut ? ` · 专用模型 ${sftOut.p.toFixed(2)}` : ` · 专用模型${sftState}`),
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

    // ---------- 3.5 号码网：判到 high/block，把这个号码记进共享信誉库 ----------
    //
    // 【为什么从 high 起记】warn 只代表"像"，老年机一天能遇到好几个；记进去会让
    //   正常快递、外卖号码被标成诈骗。high/block 是模型已经认出套路、能给出证据的那一档。
    //
    // 【为什么按"天 + 设备"去重】复盘是手动点的，同一条录音点三遍就会变成"判定 3 次"。
    //   这个次数对外要能代表"有多少台设备独立判过它"，不是"被点了多少下按钮"。
    //
    // 【为什么不存内容】号码可以共享，通话内容不能。excerpt 和 signals 一律留空 ——
    //   这一条不是可选项，是号码网敢不敢上线的前提。
    if (rank(level) >= rank('high') && number) {
      try {
        const dayStart = new Date();
        dayStart.setHours(0, 0, 0, 0);
        const dup = await db.hasReportSince(number, elderId, dayStart.getTime());
        if (!dup) {
          await db.addReport({
            elderId,
            reporter: 'callaudio',
            number,
            source: 'call',
            isFraud: true,
            stage,
            type: result.type,
            reason: result.reason,
            advice: result.advice,
            excerpt: '',      // 隐私：只共享号码，不共享通话内容
            signals: [],      // 同上，关键话术片段也不落
            localScore: local.score,
          });
          console.log(`[CallAudio] 号码网已记录：${number} S${stage}/${level}`);
        }
      } catch (e) {
        console.warn('[CallAudio] 号码网写入失败：', e.message);
      }
    }

    console.log(`[CallAudio] ${number || '未知号码'} ${fmtDur(durSec)} → S${stage}/${level}`
      + `（${basis}${sftOut ? `，专用模型 p=${sftOut.p.toFixed(2)} ${sftOut.ms}ms` : `，专用模型${sftState}`}`
      + `，转写 ${asrOut.source} ${asrMs}ms`
      + ((asrOut.parts || 1) > 1 ? `（切 ${asrOut.parts} 段，录音 ${Math.round(asrOut.durationSec)}s）` : '')
      + `，共 ${Date.now() - t0}ms）`);

    res.json({
      ok: true,
      ...result,
      pushed: !!alert,
      local: { score: local.score, stage: local.stage, why: local.why },
      asr: {
        source: asrOut.source,
        chars: transcript.length,
        ms: asrMs,
        parts: asrOut.parts || 1,
        durationSec: Math.round(asrOut.durationSec || 0),
      },
      meta: {
        ms: Date.now() - t0,
        model: ai ? pangu.model : null,
        cost: ai ? pangu.lastCost : 0,
        sft: sftOut ? { model: sft.model, p: sftOut.p, ms: sftOut.ms } : null,
      },
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
