'use strict';
/**
 * ============================================================================
 *  RateLimit.js —— AI 接口的每日次数限制（按 IP）
 * ----------------------------------------------------------------------------
 *  为什么需要它？
 *    /api/report 和 /api/drill/turn 背后是一次真实的盘古调用，按 token 计费。
 *    仓库公开之后，任何人都能看到 8080 地址，写个循环就能把 MaaS 额度刷干净。
 *    这不是"可能被人看到"的风险，是"直接花你的钱"的风险。
 *
 *  为什么放在服务端而不是 App 里？
 *    1. App 里做限流，改一次要重新打包、重新装、重新签名，现场来不及
 *    2. App 端可以被逆向绕过，服务端才是唯一可信的那道门
 *    3. 这里加一道，老人端 / 子女端 / 网页端全都自动受保护，一行客户端代码都不用动
 *
 *  存储用哪？
 *    Redis 在就写 Redis（多实例共享、重启不清零），Redis 挂了自动退进程内存。
 *    注意：退内存时进程重启会把额度重置一次，属于可接受的降级，
 *    因为这个限制防的是"脚本刷钱"，不是"精确计费"。
 *
 *  一个刻意的设计：**失败放行**。
 *    计数器自己出异常时，请求照放。宁可少扣一次额度，
 *    也不能因为限流器挂了让老人按下"发给女儿"没反应——演示现场断网是常态。
 * ============================================================================
 */

/** 按北京时间切日。服务器装在哪、系统时区是什么，都不影响"什么时候算第二天" */
function dayKey(d) {
  const x = d || new Date();
  // en-CA 输出就是 YYYY-MM-DD，省掉手工补零
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(x);
}

/** 距离北京时间次日零点还有多少秒（用来给 Redis 键设过期，不会永远占着） */
function secondsToNextDay() {
  const next = new Date(dayKey() + 'T00:00:00+08:00').getTime() + 24 * 3600 * 1000;
  return Math.max(60, Math.ceil((next - Date.now()) / 1000) + 60);
}

class RateLimit {
  /**
   * @param {import('ioredis').Redis|null} redis 没连上就传 null，自动走内存
   */
  constructor(redis) {
    this.redis = redis;
    /** @type {Map<string, {n:number, exp:number}>} */
    this.mem = new Map();
    this._sweptAt = 0;
  }

  _key(bucket, ip) {
    return `yindun:rl:${bucket}:${ip}:${dayKey()}`;
  }

  /**
   * 计一次数。
   * @param {string} bucket 业务名，如 'report' / 'drill_turn'
   * @param {string} ip     调用方地址
   * @param {number} perDay 每天允许多少次；<=0 表示不限
   * @returns {Promise<{allowed:boolean, used:number, limit:number, resetAt:number}>}
   */
  async check(bucket, ip, perDay) {
    const limit = Number(perDay) || 0;
    if (limit <= 0) {
      return { allowed: true, used: 0, limit: 0, resetAt: 0 };
    }

    const key = this._key(bucket, ip);
    const ttl = secondsToNextDay();
    const resetAt = Date.now() + ttl * 1000;

    // ---------- 优先 Redis ----------
    if (this.redis) {
      try {
        const used = await this.redis.incr(key);
        if (used === 1) await this.redis.expire(key, ttl);
        return { allowed: used <= limit, used, limit, resetAt };
      } catch (e) {
        // 掉到内存分支，别把请求带崩
        console.warn('[RateLimit] Redis 计数失败，本次用内存：', e.message);
      }
    }

    // ---------- 内存兜底 ----------
    this._sweep();
    const now = Date.now();
    let rec = this.mem.get(key);
    if (!rec || rec.exp <= now) {
      rec = { n: 0, exp: now + ttl * 1000 };
      this.mem.set(key, rec);
    }
    rec.n += 1;
    return { allowed: rec.n <= limit, used: rec.n, limit, resetAt: rec.exp };
  }

  /** 清掉过期条目，避免长期运行时 Map 越堆越大 */
  _sweep() {
    const now = Date.now();
    if (now - this._sweptAt < 10 * 60 * 1000 && this.mem.size < 2000) return;
    this._sweptAt = now;
    for (const [k, v] of this.mem) {
      if (v.exp <= now) this.mem.delete(k);
    }
  }

  /** 给健康检查用：现在内存里记了多少个 IP（不含 Redis 里的） */
  memSize() {
    return this.mem.size;
  }
}

/**
 * 取调用方 IP。
 *
 * 默认只认 TCP 连接的真实地址，**故意不读 X-Forwarded-For**——
 * 那个头客户端想写什么写什么，照着抄一句 "X-Forwarded-For: 1.2.3.4"
 * 就能每天换个身份绕过限额，等于没限。
 * 只有在前面真挂了 nginx / 负载均衡时才开 TRUST_PROXY=1。
 */
function clientIp(req, trustProxy) {
  if (trustProxy) {
    const xff = req.headers && req.headers['x-forwarded-for'];
    if (xff) {
      const first = String(xff).split(',')[0].trim();
      if (first) return first;
    }
  }
  let ip = (req.ip || (req.socket && req.socket.remoteAddress) || 'unknown');
  if (ip.indexOf('::ffff:') === 0) ip = ip.slice(7);   // IPv4 映射地址还原
  return ip;
}

module.exports = { RateLimit, clientIp, dayKey };
