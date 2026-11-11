'use strict';
/**
 * ============================================================================
 *  AudioSplit.js —— 把长录音切成 ≤ N 秒的小段
 * ----------------------------------------------------------------------------
 *  为什么需要它：
 *    华为云「一句话识别」（short-audio）硬性限制单次音频 ≤ 60 秒，超了要么报错、
 *    要么只给你前 60 秒的结果。而诈骗电话的**要害往往在最后**——"把钱转到安全
 *    账户"这句话可能在第 90 秒才出现。只识别前 60 秒，等于把最关键的证据丢掉。
 *
 *  为什么在云端切、不在手机端切：
 *    · 手机端切的方案要先改 App、再发版，且只对新录的音生效；
 *    · 云端切对所有客户端都生效，包括以后 iOS、包括历史录音补传。
 *    代价是云端要自己解析音频容器 —— 所以这里只做**帧边界切割**，不重新编码：
 *    MP3 的每帧自带帧头，AMR 每帧 20ms 定长，WAV 是裸 PCM。
 *    在这三种格式的帧边界上下刀，切出来的每一段都是**能独立播放的合法文件**，
 *    音质零损失、零 CPU 转码。
 *
 *  不支持的格式（m4a/aac/3gp/ogg，容器里有全局索引，切开就废）：
 *    返回 null，由调用方决定怎么办（现在的做法是整段直接送，超时了如实报错）。
 *
 *  参考：
 *    · MP3 帧头：ISO/IEC 11172-3 / 13818-3
 *    · AMR-NB：3GPP TS 26.101，每帧 20ms
 * ============================================================================
 */

// ---------------------------------------------------------------- MP3 --------
// 码率表（kbps）。索引 0 = free，15 = 非法
const MP3_BITRATE_V1L3 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0];
const MP3_BITRATE_V2L3 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0];
// 采样率表（Hz）
const MP3_RATE = {
  3: [44100, 48000, 32000], // MPEG 1
  2: [22050, 24000, 16000], // MPEG 2
  0: [11025, 12000, 8000],  // MPEG 2.5
};

/** 跳过 ID3v2 标签，返回音频数据的起始偏移 */
function mp3AudioStart(buf) {
  if (buf.length < 10 || buf.toString('latin1', 0, 3) !== 'ID3') return 0;
  // ID3v2 头第 6~9 字节是 syncsafe 长度（每字节只用低 7 位）
  const size = ((buf[6] & 0x7f) << 21) | ((buf[7] & 0x7f) << 14) |
               ((buf[8] & 0x7f) << 7) | (buf[9] & 0x7f);
  return 10 + size + ((buf[5] & 0x10) ? 10 : 0); // 有 footer 再加 10
}

/** 解析一个 MP3 帧头；不是合法帧头返回 null */
function mp3Frame(buf, off) {
  if (off + 4 > buf.length) return null;
  if (buf[off] !== 0xff || (buf[off + 1] & 0xe0) !== 0xe0) return null;
  const b1 = buf[off + 1];
  const b2 = buf[off + 2];
  const b3 = buf[off + 3];
  const verBits = (b1 >> 3) & 0x03;      // 3=MPEG1, 2=MPEG2, 0=MPEG2.5, 1=保留
  const layerBits = (b1 >> 1) & 0x03;    // 1=Layer III
  if (verBits === 1 || layerBits === 0) return null;
  const brIdx = (b2 >> 4) & 0x0f;
  const srIdx = (b2 >> 2) & 0x03;
  if (brIdx === 0 || brIdx === 15 || srIdx === 3) return null;
  const padding = (b2 >> 1) & 0x01;
  const sampleRate = MP3_RATE[verBits][srIdx];
  const bitrate = (verBits === 3 ? MP3_BITRATE_V1L3[brIdx] : MP3_BITRATE_V2L3[brIdx]) * 1000;
  if (!bitrate || !sampleRate) return null;

  const isLayer1 = layerBits === 3;
  const len = isLayer1
    ? Math.floor((12 * bitrate) / sampleRate + padding) * 4
    : Math.floor(((layerBits === 1 && verBits !== 3 ? 72 : 144) * bitrate) / sampleRate + padding);
  if (len < 4) return null;

  // 每帧的采样数 → 时长
  // 层号位（第 19~18 位）：3=Layer I，2=Layer II，1=Layer III
  let samples;
  if (isLayer1) samples = 384;
  else if (layerBits === 2) samples = 1152;              // Layer II
  else samples = verBits === 3 ? 1152 : 576;             // Layer III
  return { len, sec: samples / sampleRate };
}

function splitMp3(buf, maxSec) {
  const start = mp3AudioStart(buf);
  const parts = [];
  let cur = start;
  let curSec = 0;
  let pos = start;

  while (pos < buf.length) {
    const f = mp3Frame(buf, pos);
    if (!f) break; // 帧头断了，剩下的（多半是 ID3v1 尾巴）跟最后一段走
    if (curSec > 0 && curSec + f.sec > maxSec) {
      parts.push(buf.subarray(cur, pos));
      cur = pos;
      curSec = 0;
    }
    curSec += f.sec;
    pos += f.len;
  }
  if (pos < buf.length && parts.length) {
    // 尾部残留（ID3v1 标签等非音频字节）挂到最后一段，不另开一段
    parts[parts.length - 1] = buf.subarray(cur);
  } else if (pos > cur) {
    parts.push(buf.subarray(cur, pos));
  }
  return { parts, durationSec: totalMp3Sec(buf) };
}

function totalMp3Sec(buf) {
  const start = mp3AudioStart(buf);
  let pos = start;
  let sec = 0;
  while (pos < buf.length) {
    const f = mp3Frame(buf, pos);
    if (!f) break;
    sec += f.sec;
    pos += f.len;
  }
  return sec;
}

// ---------------------------------------------------------------- AMR --------
// AMR-NB 每帧字节数 = 表值 + 1
const AMR_NB_SIZE = [12, 13, 15, 17, 19, 20, 26, 31, 5, 0, 0, 0, 0, 0, 0, 0];
const AMR_WB_SIZE = [17, 23, 32, 36, 40, 46, 50, 58, 60, 5, 0, 0, 0, 0, 0, 0];

function splitAmr(buf, maxSec) {
  const head = buf.toString('latin1', 0, 9);
  let off;
  let sizes;
  if (head.startsWith('#!AMR-WB')) { off = 9; sizes = AMR_WB_SIZE; }
  else if (head.startsWith('#!AMR')) { off = 6; sizes = AMR_NB_SIZE; }
  else return null;

  const framesPerPart = Math.max(1, Math.floor(maxSec / 0.02)); // 每帧 20ms
  const parts = [];
  let curStart = 0; // 每段都带上文件头，保证能独立播放
  let pos = off;
  let count = 0;
  let total = 0;

  while (pos < buf.length) {
    const ft = (buf[pos] >> 3) & 0x0f;
    const sz = sizes[ft] + 1;
    if (!sizes[ft] || pos + sz > buf.length) break;
    pos += sz;
    count += 1;
    total += 1;
    if (count === framesPerPart) {
      parts.push(buf.subarray(curStart, pos));
      curStart = pos;
      count = 0;
    }
  }
  if (pos > curStart) parts.push(buf.subarray(curStart, buf.length));
  return { parts, durationSec: total * 0.02 };
}

// ---------------------------------------------------------------- WAV --------
function splitWav(buf, maxSec) {
  if (buf.toString('latin1', 0, 4) !== 'RIFF' || buf.toString('latin1', 8, 12) !== 'WAVE') return null;
  let pos = 12;
  let fmt = null;
  let dataOff = -1;
  let dataLen = 0;
  while (pos + 8 <= buf.length) {
    const id = buf.toString('latin1', pos, pos + 4);
    const len = buf.readUInt32LE(pos + 4);
    if (id === 'fmt ') {
      fmt = {
        channels: buf.readUInt16LE(pos + 10),
        sampleRate: buf.readUInt32LE(pos + 12),
        blockAlign: buf.readUInt16LE(pos + 20),
        bits: buf.readUInt16LE(pos + 22),
      };
    } else if (id === 'data') {
      dataOff = pos + 8;
      dataLen = Math.min(len, buf.length - dataOff);
      break;
    }
    pos += 8 + len + (len % 2);
  }
  if (!fmt || dataOff < 0 || !fmt.blockAlign) return null;

  const bytesPerSec = fmt.sampleRate * fmt.blockAlign;
  const durationSec = dataLen / bytesPerSec;
  const maxBytes = Math.floor(maxSec * bytesPerSec / fmt.blockAlign) * fmt.blockAlign;
  if (maxBytes <= 0) return null;

  const header = Buffer.from(buf.subarray(0, dataOff));
  const parts = [];
  for (let s = 0; s < dataLen; s += maxBytes) {
    const end = Math.min(s + maxBytes, dataLen);
    const chunkLen = end - s;
    // 每段重写 RIFF/data 长度字段，否则播放器读不到真实长度
    const h = Buffer.from(header);
    h.writeUInt32LE(36 + chunkLen, 4);
    const dataIdx = dataOff - 8;
    h.writeUInt32LE(chunkLen, dataIdx + 4);
    parts.push(Buffer.concat([h, buf.subarray(dataOff + s, dataOff + end)]));
  }
  return { parts, durationSec };
}

// ------------------------------------------------------------- 统一入口 ------

/**
 * 把音频切成 ≤ maxSec 秒的若干段。
 * @returns {{parts: Buffer[], durationSec: number, format: string}|null}
 *   支持的格式返回切好的段；不支持的格式返回 null（调用方整段送）。
 */
function split(buf, name, maxSec = 55) {
  const m = String(name || '').toLowerCase().match(/\.([a-z0-9]+)$/);
  const ext = m ? m[1] : 'mp3';
  let out = null;
  if (ext === 'mp3') out = splitMp3(buf, maxSec);
  else if (ext === 'amr' || ext === 'awb') out = splitAmr(buf, maxSec);
  else if (ext === 'wav') out = splitWav(buf, maxSec);
  if (!out || !out.parts.length) return null;
  return { ...out, format: ext };
}

module.exports = { split, totalMp3Sec };
