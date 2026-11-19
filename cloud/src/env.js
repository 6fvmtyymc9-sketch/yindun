'use strict';
/**
 * env.js —— 加载项目根目录的 .env 文件（自实现，不引第三方依赖）
 *
 * .env 存 API Key 等敏感配置，已被 .gitignore 排除，绝不进仓库。
 * 任何入口（服务、测试脚本）第一行 require 本模块即可。
 *
 * 格式：KEY=VALUE，一行一个，# 开头是注释。
 *
 * 两条优先级规则（2026-11-19 明确）：
 *   1) 系统环境变量优先于 .env —— .env 只填空，不覆盖已有的值
 *   2) .env 文件内同名键，后出现的覆盖先出现的 —— 与标准 dotenv 一致
 */

const path = require('path');
const fs = require('fs');

const envPath = path.join(__dirname, '..', '.env');
if (fs.existsSync(envPath)) {
  // 【2026-11-19 改】原来是边读边写、同键取**第一个**值，与标准 dotenv 正好相反。
  // 实际踩过：运维用 >> 追加了一行同名配置，重启后仍是旧值，排查半天。
  // 现在先整理解析（后者覆盖前者），再统一灌进 process.env（已存在的系统变量不动）。
  const parsed = new Map();
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z_0-9]+)\s*=\s*(.*)\s*$/);
    if (m) parsed.set(m[1], m[2]);
  }
  for (const [k, v] of parsed) {
    if (process.env[k] === undefined) process.env[k] = v;
  }
}
