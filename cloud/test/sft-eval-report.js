#!/usr/bin/env node
/**
 * 评估集上的 SFT 音频模式打分（老师《SFT 下一步计划》第 2 步的 SFT 两行）
 *
 * 输入：
 *   cloud/test/calls_eval/labels.json         人工标签（正常/诈骗）
 *   cloud/test/calls_eval/out-sft-judge.jsonl  GPU 上 /judge 的逐条原始返回
 * 输出：
 *   cloud/test/calls_eval/out-sft-judge.json  结构化报告（阈值扫描 + 逐条）
 *
 * 生成 out-sft-judge.jsonl 的办法（在有 GPU 的机器上）：
 *   ~/yindun/run_eval30.py  →  ~/yindun/eval30_out.jsonl
 * 注意 p 的含义：p = 模型自报 confidence（判诈骗时）或 1-confidence（判正常时），
 * 即「判为诈骗的把握」，不是统计概率。
 *
 * 用法：node cloud/test/sft-eval-report.js
 */
'use strict';
const fs = require('fs');
const path = require('path');

const DIR = path.join(__dirname, 'calls_eval');
const labels = JSON.parse(fs.readFileSync(path.join(DIR, 'labels.json'), 'utf8')).items;
const raw = fs
  .readFileSync(path.join(DIR, 'out-sft-judge.jsonl'), 'utf8')
  .trim()
  .split('\n')
  .map((l) => JSON.parse(l));

const byFile = {};
for (const r of raw) byFile[r.file] = r;

const rows = labels.map((l) => {
  const base = l.file.split('/').pop();
  const r = byFile[base] || {};
  return {
    file: base,
    label: l.label,
    scenario: l.scenario,
    p: r.p == null ? null : r.p,
    isFraud: r.is_fraud == null ? null : r.is_fraud,
    scene: r.scene || null,
    ms: r._ms || null,
    durSec: r.durSec == null ? null : r.durSec,
    voicedSec: r.voicedSec == null ? null : r.voicedSec,
    skipped: !!r.skipped,
    reason: r.reason || null,
    error: r.error || null,
  };
});

const pos = rows.filter((x) => x.label === '诈骗');
const neg = rows.filter((x) => x.label === '正常');
const pOf = (x) => (x.p == null ? -1 : x.p);
const q = (arr, f) => {
  const s = arr.slice().sort((a, b) => a - b);
  if (!s.length) return null;
  return s[Math.min(s.length - 1, Math.floor(s.length * f))];
};

const sweep = [0.5, 0.7, 0.75, 0.8, 0.82, 0.85, 0.9, 0.95].map((t) => {
  const hit = pos.filter((x) => pOf(x) >= t).length;
  const fp = neg.filter((x) => pOf(x) >= t).length;
  return {
    threshold: t,
    recall: hit + '/' + pos.length,
    recallRate: +(hit / pos.length).toFixed(4),
    falsePositives: fp,
    fpr: +(fp / neg.length).toFixed(4),
  };
});

const at85 = sweep.find((s) => s.threshold === 0.85);
const times = rows.map((x) => x.ms).filter(Boolean);
const missed = pos.filter((x) => pOf(x) < 0.85).map((x) => x.file);
const overP = neg.filter((x) => pOf(x) >= 0.5).sort((a, b) => pOf(b) - pOf(a));

const report = {
  generatedAt: new Date().toISOString(),
  dataset: 'cloud/test/calls_eval/labels.json',
  source: 'out-sft-judge.jsonl（GPU 上 tools/sft_server.py /judge，原生两轮问法）',
  frozen: false,
  counts: { total: rows.length, fraud: pos.length, normal: neg.length },
  pMeaning: 'p = 判为诈骗的把握（判诈骗时取 confidence，判正常时取 1-confidence），非统计概率',
  chosen: { threshold: 0.85, ...at85 },
  sweep,
  timingMs: { median: q(times, 0.5), p90: q(times, 0.9), max: Math.max(...times), min: Math.min(...times) },
  fraudMin: Math.min(...pos.map(pOf)),
  normalMax: Math.max(...neg.map(pOf)),
  missedAt85: missed,
  falsePositivesAt05: overP.map((x) => ({ file: x.file, p: x.p, scene: x.scene })),
  rows,
};

fs.writeFileSync(path.join(DIR, 'out-sft-judge.json'), JSON.stringify(report, null, 2) + '\n', 'utf8');

console.log('样本 ' + rows.length + '（诈骗 ' + pos.length + ' / 正常 ' + neg.length + '）');
console.log('\n阈值   召回      误报      误报数');
for (const s of sweep) {
  console.log(
    ' ' + s.threshold.toFixed(2) + '  ' + s.recall.padStart(7) + '  ' + s.falsePositives + '/' + neg.length +
    '  ' + String(s.falsePositives).padStart(6)
  );
}
console.log('\np 分界：诈骗最低 ' + report.fraudMin + ' / 正常最高 ' + report.normalMax);
console.log('耗时 中位 ' + report.timingMs.median + 'ms / P90 ' + report.timingMs.p90 + 'ms / 最大 ' + report.timingMs.max + 'ms');
console.log('0.85 阈值下漏报：' + (missed.length ? missed.join(', ') : '无'));
console.log('→ 已写 ' + path.join(DIR, 'out-sft-judge.json'));
