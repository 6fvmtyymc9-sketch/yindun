'use strict';
/**
 * WAV 重采样 → 16kHz 单声道 16bit。
 *
 * 【为什么需要】
 * 2026-11-21 实测第一条真人录音（iPhone 自带通话录音，开免提，导出 wav）：
 *   单声道 / 16bit / **44100 Hz** / 15.92 秒
 * 而 Asr.js 的 SIS property 默认是 `chinese_8k_common`（8k 模型）。
 * 把 44.1k 音频塞给 8k 模型相当于 5.5 倍速快放，转写结果要么是废字、要么直接报错。
 * 这就是"录音上传链路一直不通"最可能的原因——问题不在网络，在采样率。
 *
 * 【为什么在服务端做】
 * 手机端转格式要么得装 App、要么得连电脑，老人和队友都折腾不动。
 * wav 是裸 PCM，重采样几十行就能写完，比让录音的人去折腾转码省事得多。
 *
 * 【只处理 wav PCM16，其它一律返回 null】
 * amr / mp3 本身多为 8k 窄带，送 8k 模型是对的，不动。
 * 非 PCM（比如浮点 wav、ADPCM）不改写，宁可让它按原样走、失败了报错，
 * 也不要在服务端悄悄产出一段听不出问题的错音频。
 */

/** 读 RIFF/WAVE 头，返回 {audioFormat, channels, sampleRate, bitsPerSample, dataOffset, dataLen} */
function parseWav(buf) {
  if (!buf || buf.length < 44) return null;
  if (buf.toString('ascii', 0, 4) !== 'RIFF') return null;
  if (buf.toString('ascii', 8, 12) !== 'WAVE') return null;

  let fmt = null;
  let pos = 12;
  while (pos + 8 <= buf.length) {
    const id = buf.toString('ascii', pos, pos + 4);
    const size = buf.readUInt32LE(pos + 4);
    const body = pos + 8;
    if (id === 'fmt ' && body + 16 <= buf.length) {
      fmt = {
        audioFormat: buf.readUInt16LE(body),
        channels: buf.readUInt16LE(body + 2),
        sampleRate: buf.readUInt32LE(body + 4),
        bitsPerSample: buf.readUInt16LE(body + 14),
      };
    } else if (id === 'data') {
      if (!fmt) return null;
      // 有些录音机把头里的 data 长度写成 0 或 0xFFFFFFFF（边录边写），用实际剩余长度兜底
      const len = Math.min(size, buf.length - body);
      return Object.assign(fmt, { dataOffset: body, dataLen: len });
    }
    pos = body + size + (size % 2);   // RIFF 要求 chunk 按偶数对齐
  }
  return null;
}

/** 拼一个 44 字节的标准 PCM wav 头 */
function wavHeader(dataLen, sampleRate, channels, bitsPerSample) {
  const h = Buffer.alloc(44);
  const blockAlign = channels * (bitsPerSample / 8);
  h.write('RIFF', 0, 'ascii');
  h.writeUInt32LE(36 + dataLen, 4);
  h.write('WAVE', 8, 'ascii');
  h.write('fmt ', 12, 'ascii');
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20);                 // PCM
  h.writeUInt16LE(channels, 22);
  h.writeUInt32LE(sampleRate, 24);
  h.writeUInt32LE(sampleRate * blockAlign, 28);
  h.writeUInt16LE(blockAlign, 32);
  h.writeUInt16LE(bitsPerSample, 34);
  h.write('data', 36, 'ascii');
  h.writeUInt32LE(dataLen, 40);
  return h;
}

/**
 * 转成 16kHz 单声道 16bit。
 * @returns {Buffer|null} null 表示不需要动（已经是 16k 单声道 / 不是 wav PCM16 / 解析失败）
 */
function resampleTo16k(buf, targetRate) {
  const want = Number(targetRate) || 16000;
  const info = parseWav(buf);
  if (!info) return null;
  if (info.audioFormat !== 1) return null;          // 只认 PCM
  if (info.bitsPerSample !== 16) return null;       // 只认 16bit
  if (info.channels < 1 || info.channels > 2) return null;
  if (info.sampleRate === want && info.channels === 1) return null;  // 已经合格

  const frames = Math.floor(info.dataLen / (2 * info.channels));
  if (frames < 2) return null;

  // 1) 下混成单声道
  const mono = new Float32Array(frames);
  for (let i = 0; i < frames; i += 1) {
    let v = 0;
    for (let c = 0; c < info.channels; c += 1) {
      v += buf.readInt16LE(info.dataOffset + (i * info.channels + c) * 2);
    }
    mono[i] = v / info.channels;
  }

  const ratio = info.sampleRate / want;
  const outFrames = Math.max(2, Math.floor(frames / ratio));

  // 2) 区间平均后取一点：这既是重采样也顺手做了一次低通，
  //    直接隔点抽会把 8k 以上的成分折回语音频段，听起来发闷、ASR 反而更差。
  const out = Buffer.alloc(outFrames * 2);
  for (let i = 0; i < outFrames; i += 1) {
    const a = i * ratio;
    const b = Math.min(frames, (i + 1) * ratio);
    let sum = 0;
    let cnt = 0;
    for (let p = Math.floor(a); p < b; p += 1) {
      sum += mono[p];
      cnt += 1;
    }
    if (cnt === 0) {   // 上采样时区间不足一个源样本，退化成线性插值
      const p0 = Math.min(frames - 1, Math.floor(a));
      const p1 = Math.min(frames - 1, p0 + 1);
      const frac = a - Math.floor(a);
      sum = mono[p0] * (1 - frac) + mono[p1] * frac;
      cnt = 1;
    }
    let v = Math.round(sum / cnt);
    if (v > 32767) v = 32767;
    if (v < -32768) v = -32768;
    out.writeInt16LE(v, i * 2);
  }

  return Buffer.concat([wavHeader(out.length, want, 1, 16), out]);
}

module.exports = { resampleTo16k, parseWav };
