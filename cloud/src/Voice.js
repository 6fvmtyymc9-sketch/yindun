'use strict';
/**
 * ============================================================================
 *  Voice.js —— 亲情语音盾（子女录的那一句）
 * ----------------------------------------------------------------------------
 *  为什么要有这个：
 *    盖红屏那一刻，如果响的是机器合成音，骗子当场就能拆台——
 *    "那是诈骗软件，机器人的声音，快关掉。"老人正被他牵着，信谁？
 *    换成女儿本人的声音，骗子就没话说了：他解释不了"为什么你闺女在骂你"。
 *
 *  三个接口：
 *    POST /api/voice        子女端上传一段录音（base64）
 *    GET  /api/voice/meta   老人端先问"有没有、是不是新的"（几百字节）
 *    GET  /api/voice        老人端把音频取走，存到手机本地
 *
 *  【关键设计：老人端必须提前缓存】
 *    拦截那一刻不能现下载。网慢两秒，钱就转出去了。
 *    所以老人端开机拉一次、之后每 6 小时问一次 meta，有新版本才下载。
 *
 *  【存哪】
 *    cloud/data/voice/<elderId>.<ext>，加一个 .json 记 mime/大小/时间。
 *    不上 RDS：几秒钟的音频放 BLOB 里，备份、迁移都变重，文件系统更合适。
 *    部署时 data/ 要单独保留，别跟着代码覆盖掉。
 *
 *  【隐私】
 *    · 只存子女自己录的这一句，不涉及老人通话内容
 *    · 老人端身份只能取自己那份（elderId 由身份中间件强制覆盖）
 * ============================================================================
 */

const express = require('express');
const fs = require('fs');
const path = require('path');

/** 5~10 秒的一句话。给到 4MB 已经很宽松，超过必然是别的东西 */
const MAX_BYTES = 4 * 1024 * 1024;

const MIME_EXT = {
  'audio/mp4': 'm4a',
  'audio/m4a': 'm4a',
  'audio/x-m4a': 'm4a',
  'audio/aac': 'm4a',
  'audio/mpeg': 'mp3',
  'audio/mp3': 'mp3',
  'audio/wav': 'wav',
  'audio/x-wav': 'wav',
  'audio/wave': 'wav',
  'audio/ogg': 'ogg',
  'audio/webm': 'webm',
  'audio/amr': 'amr',
};

const EXT_MIME = {
  m4a: 'audio/mp4',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  ogg: 'audio/ogg',
  webm: 'audio/webm',
  amr: 'audio/amr',
};

function registerVoice(app, { dir } = {}) {
  const root = dir || path.join(__dirname, '..', 'data', 'voice');
  try { fs.mkdirSync(root, { recursive: true }); } catch (e) { /* 已有 */ }

  const metaPath = (elderId) => path.join(root, safeId(elderId) + '.json');
  const audioPath = (elderId, ext) => path.join(root, safeId(elderId) + '.' + ext);

  /** elderId 会被拼进文件名，必须掐掉路径分隔符，否则能写出目录 */
  function safeId(v) {
    const s = String(v || '').trim().replace(/[^A-Za-z0-9_-]/g, '');
    return s.length >= 1 ? s.slice(0, 40) : 'demo';
  }

  function readMeta(elderId) {
    try {
      return JSON.parse(fs.readFileSync(metaPath(elderId), 'utf8'));
    } catch (e) {
      return null;
    }
  }

  // --------------------------------------------------------------------------
  // 上传
  // --------------------------------------------------------------------------
  app.post('/api/voice', express.json({ limit: '6mb' }), async (req, res) => {
    const b = req.body || {};
    // 身份中间件已经把 elderId 换成真实配对的那一位；演示模式才用 body 里的
    const elderId = req.yd.elderId || b.elderId || 'demo';

    const mime = String(b.mime || 'audio/mp4').toLowerCase().split(';')[0].trim();
    const ext = MIME_EXT[mime];
    if (!ext) {
      return res.status(400).json({
        ok: false,
        msg: '不认识的音频格式：' + mime + '（支持 m4a/mp3/wav/ogg/amr）',
      });
    }

    const raw = String(b.audio || '');
    if (!raw) return res.status(400).json({ ok: false, msg: '缺少 audio（base64）' });

    let buf;
    try {
      buf = Buffer.from(raw.replace(/^data:[^,]+,/, ''), 'base64');
    } catch (e) {
      return res.status(400).json({ ok: false, msg: 'base64 解析失败' });
    }
    if (buf.length < 1024) {
      return res.status(400).json({ ok: false, msg: '音频太短，像是空文件' });
    }
    if (buf.length > MAX_BYTES) {
      return res.status(413).json({ ok: false, msg: '音频太大，请控制在 10 秒以内' });
    }

    // 先写临时文件再改名：下载端永远看不到写了一半的文件
    const tmp = audioPath(elderId, ext) + '.tmp';
    const final = audioPath(elderId, ext);
    try {
      fs.writeFileSync(tmp, buf);
      fs.renameSync(tmp, final);
    } catch (e) {
      try { fs.unlinkSync(tmp); } catch (e2) { /* 没有就算了 */ }
      return res.status(500).json({ ok: false, msg: '保存失败：' + e.message });
    }

    // 换了扩展名时，把上一份不同后缀的清掉，免得老人端取到旧的
    for (const other of Object.keys(EXT_MIME)) {
      if (other === ext) continue;
      try { fs.unlinkSync(audioPath(elderId, other)); } catch (e) { /* 本来就没有 */ }
    }

    const meta = {
      elderId,
      ext,
      mime: EXT_MIME[ext],
      bytes: buf.length,
      durSec: Number(b.durSec) || 0,
      from: String(b.from || '').slice(0, 12) || '家人',
      at: Date.now(),
    };
    try { fs.writeFileSync(metaPath(elderId), JSON.stringify(meta)); } catch (e) { /* 读的时候会兜底 */ }

    console.log(`[voice] ${elderId} ← 收到 ${(buf.length / 1024).toFixed(0)}KB ${ext}（${meta.from}）`);
    res.json({ ok: true, bytes: meta.bytes, at: meta.at, from: meta.from });
  });

  // --------------------------------------------------------------------------
  // 老人端先问一句：有没有？是不是新的？
  // 只回几个字节，不要为了判断"要不要下载"先把 1MB 音频拖下来
  // --------------------------------------------------------------------------
  app.get('/api/voice/meta', (req, res) => {
    const elderId = req.yd.elderId || req.query.elderId || 'demo';
    const m = readMeta(elderId);
    if (!m || !m.at) return res.json({ ok: true, exists: false });
    res.json({ ok: true, exists: true, at: m.at, bytes: m.bytes, mime: m.mime, from: m.from });
  });

  // --------------------------------------------------------------------------
  // 取音频
  // --------------------------------------------------------------------------
  app.get('/api/voice', (req, res) => {
    const elderId = req.yd.elderId || req.query.elderId || 'demo';
    const m = readMeta(elderId);
    if (!m || !m.ext) return res.status(404).json({ ok: false, msg: '还没有录过提醒语音' });
    const f = audioPath(elderId, m.ext);
    if (!fs.existsSync(f)) return res.status(404).json({ ok: false, msg: '录音文件不见了' });

    res.setHeader('Content-Type', m.mime || 'audio/mp4');
    res.setHeader('Content-Length', String(fs.statSync(f).size));
    // 老人端要能判断"这份是不是已经存过了"
    res.setHeader('X-Yd-Voice-At', String(m.at || 0));
    fs.createReadStream(f).pipe(res);
  });

  return {
    /** 给健康检查用：有没有人录过 */
    stats() {
      try {
        return fs.readdirSync(root).filter((f) => f.endsWith('.json')).length;
      } catch (e) {
        return 0;
      }
    },
  };
}

module.exports = { registerVoice };
