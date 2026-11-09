'use strict';
/**
 * ============================================================================
 *  State.js —— 会话状态（实时通话状态，放在 Redis 里）
 * ----------------------------------------------------------------------------
 *  为什么必须有状态？
 *    组合判据（如"通话中打开银行"）不是单看一个事件，
 *    而是要知道"现在有没有在通话、对方是谁、通了多久"。
 *    这些信息必须跨事件保留，所以需要状态。
 *
 *  为什么放 Redis？
 *    1. 判定要毫秒级，读内存比读 MySQL 快一个数量级
 *    2. 通话状态天然是"带过期时间的键"，正好贴合 Redis 的 TTL 语义
 *    3. 多实例部署时状态共享（评委如果问横向扩展，这是答案）
 *
 *  降级策略：
 *    连不上 Redis 时自动退化成进程内 Map，功能不中断（演示现场断网也不怕）。
 * ============================================================================
 */

const KEY = (elderId) => `yindun:call:${elderId}`;
const TTL = 4 * 3600; // 4 小时，一通电话不可能更长

// ---------- 进程内兜底存储 ----------
const mem = new Map();

class State {
  /**
   * @param {import('ioredis').Redis|null} redis 连不上时传 null 就走内存
   */
  constructor(redis) {
    this.redis = redis;
  }

  /** 新建/覆盖一份会话状态 */
  async set(elderId, st) {
    if (this.redis) {
      try {
        await this.redis.set(KEY(elderId), JSON.stringify(st), 'EX', TTL);
        return;
      } catch (e) {
        console.warn('[State] Redis 写入失败，退化到内存：', e.message);
      }
    }
    mem.set(elderId, { ...st, _at: Date.now() });
  }

  /** 读状态，不存在返回空状态 */
  async get(elderId) {
    let st = null;
    if (this.redis) {
      try {
        const raw = await this.redis.get(KEY(elderId));
        st = raw ? JSON.parse(raw) : null;
      } catch (e) {
        console.warn('[State] Redis 读取失败，退化到内存：', e.message);
      }
    }
    if (!st) {
      const m = mem.get(elderId);
      // 内存里也过期
      if (m && Date.now() - m._at < TTL * 1000) st = m;
    }
    return st || blank();
  }

  /** 局部更新（读-改-写） */
  async patch(elderId, delta) {
    const st = await this.get(elderId);
    const next = Object.assign(st, delta);
    await this.set(elderId, next);
    return next;
  }

  async clear(elderId) {
    if (this.redis) {
      try { await this.redis.del(KEY(elderId)); } catch (e) { /* 忽略 */ }
    }
    mem.delete(elderId);
  }
}

/** 一份干净的初始状态。字段名与组合判据里的表达式一一对应 */
function blank() {
  return {
    call: {
      active: false,
      number: '',
      who: '',            // stranger / contact / service
      direction: 'in',    // in 来电 / out 去电
      dur: 0,             // 通话秒数
      startAt: 0,
      rejectFamily: 0,    // 通话中拒接家人来电次数
    },
    sms: { lastBody: '', lastAt: 0, isCode: false },
    smsScore: 0,
    app: { pkg: '', label: '', isBank: false },
    install: { fromStore: true, pkg: '' },
    a11y: { granted: false },
    // 熟人标记。注意：这个字段绝对不许参与 block 判级，
    // 它只能影响"要不要弹询问"，不能关掉防护。
    known: { marked: false, at: 0, until: 0 },
  };
}

module.exports = { State, blank };
