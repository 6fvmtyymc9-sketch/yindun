'use strict';
/**
 * env.js —— 加载项目根目录的 .env 文件（自实现，不引第三方依赖）
 *
 * .env 存 API Key 等敏感配置，已被 .gitignore 排除，绝不进仓库。
 * 任何入口（服务、测试脚本）第一行 require 本模块即可。
 *
 * 格式：KEY=VALUE，一行一个，# 开头是注释。
 * 已存在的环境变量不会被覆盖（系统环境变量优先）。
 */

const path = require('path');
const fs = require('fs');

const envPath = path.join(__dirname, '..', '.env');
if (fs.existsSync(envPath)) {
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z_0-9]+)\s*=\s*(.*)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2];
  }
}
