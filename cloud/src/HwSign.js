'use strict';
/**
 * ============================================================================
 *  HwSign.js —— 华为云 API 的 AK/SK 签名（SDK-HMAC-SHA256）
 * ----------------------------------------------------------------------------
 *  为什么要自己实现：
 *    官方 Node SDK 覆盖不全，SIS 的 short-audio 是普通 REST 接口，
 *    直接按《API 签名指南》算一个 Authorization 头就够了，不必多引一个包。
 *
 *  为什么用 AK/SK 而不是账号密码：
 *    密码认证要走 IAM 换 token，token 24 小时过期，且密码一旦改了就全线断。
 *    AK/SK 是给程序用的永久凭证，可以在控制台随时停用/删除，泄漏了也不必改密码。
 *
 *  签名三步（华为云规范）：
 *    1. CanonicalRequest = Method \n URI \n Query \n CanonicalHeaders \n SignedHeaders \n SHA256(body)
 *    2. StringToSign     = "SDK-HMAC-SHA256" \n X-Sdk-Date \n SHA256(CanonicalRequest)
 *    3. Signature        = HMAC-SHA256(SK, StringToSign)
 *  Authorization: SDK-HMAC-SHA256 Access=<AK>, SignedHeaders=<...>, Signature=<...>
 *
 *  注意：X-Sdk-Date 必须是 UTC，格式 20261111T043000Z，且和服务端时间差不能超过 15 分钟。
 * ============================================================================
 */

const crypto = require('crypto');

/** UTC 时间，形如 20261111T043000Z */
function sdkDate(now = Date.now()) {
  return new Date(now).toISOString().replace(/[-:]|\.\d{3}/g, '');
}

function sha256Hex(data) {
  return crypto.createHash('sha256').update(data, 'utf8').digest('hex');
}

/** 按 RFC 3986 的非保留字符集做 URL 编码（华为云 urlEncode 等价实现） */
function uriEncode(str) {
  return String(str).replace(/[^A-Za-z0-9\-._~]/g, (c) => {
    return '%' + c.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0');
  });
}

/**
 * 华为云的 CanonicalURI —— 这是最容易踩的一个坑，务必别"优化"掉：
 *   官方 SDK（AKSKSigner.CanonicalURI）在拼完路径后，若末尾不是 '/' 会**强制补一个**。
 *   例如实际请求 /v1/{pid}/asr/short-audio，参与签名的是 /v1/{pid}/asr/short-audio/
 *   少这一个字符，服务端算出来的签名就和你不一样，返回 APIG.0301 verify ak sk signature fail。
 */
function canonicalUri(pathname) {
  const parts = String(pathname || '/').split('/').map(uriEncode);
  let p = parts.join('/');
  if (!p.endsWith('/')) p += '/';
  return p;
}

/**
 * 给一个请求签名。
 * @param {object} opt
 * @param {string} opt.method   GET / POST
 * @param {string} opt.url      完整 URL（含 query）
 * @param {object} [opt.headers] 已有的头（Content-Type 等），会被一起签进去
 * @param {string} [opt.body]   请求体原文（空请求传 ''）
 * @param {string} opt.ak
 * @param {string} opt.sk
 * @returns {object} 补好 Authorization / X-Sdk-Date 的头对象
 */
function signRequest({ method, url, headers = {}, body = '', ak, sk, now }) {
  if (!ak || !sk) throw new Error('AK/SK 未配置');

  const u = new URL(url);
  const date = sdkDate(now);

  // 参与签名的头：Content-Type、Host、X-Sdk-Date 是华为云要求的必备三项
  const all = {
    ...headers,
    Host: u.host,
    'X-Sdk-Date': date,
  };

  // 头名统一小写、值去首尾空格，按名字字典序排列
  const lower = {};
  for (const [k, v] of Object.entries(all)) {
    const name = k.toLowerCase();
    if (v === undefined || v === null) continue;
    lower[name] = String(v).trim();
  }
  const names = Object.keys(lower).sort();
  const canonicalHeaders = names.map((n) => `${n}:${lower[n]}\n`).join('');
  const signedHeaders = names.join(';');

  // query 要按 key 排序；本项目接口没有 query，留空即可
  const query = [...u.searchParams.entries()]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([k, v]) => `${uriEncode(k)}=${uriEncode(v)}`)
    .join('&');

  const canonicalRequest = [
    method.toUpperCase(),
    canonicalUri(u.pathname),
    query,
    canonicalHeaders, // 自身已以 \n 结尾，join 会再补一个，形成规范要求的空行
    signedHeaders,
    sha256Hex(body),
  ].join('\n');

  const stringToSign = [
    'SDK-HMAC-SHA256',
    date,
    sha256Hex(canonicalRequest),
  ].join('\n');

  const signature = crypto
    .createHmac('sha256', sk)
    .update(stringToSign, 'utf8')
    .digest('hex');

  const out = { ...headers, 'X-Sdk-Date': date };
  out.Authorization =
    `SDK-HMAC-SHA256 Access=${ak}, SignedHeaders=${signedHeaders}, Signature=${signature}`;
  return out;
}

module.exports = { signRequest, sdkDate };
