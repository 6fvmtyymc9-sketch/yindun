/**
 * 用大模型扩充训练样本（**只补训练集，绝不碰 holdout.json**）
 *
 * 背景：cases.json 只有 59 条诈骗样本，模型会把它们背下来。
 * 《模型选型》4.1 要求训练数据包含 ChiFraud + cases.json + 正常短信，
 * 但 ChiFraud 实测是网页黑灰产广告（招嫖/赌博/办假证），与"冒充公检法骗转账"
 * 不是同一种东西，补不进这个场景。所以正样本改用同域扩写：
 * 以 cases.json 里已有的 12 类套路为模板，让大模型换说法生成变体。
 *
 * 纪律（写死在代码里）：
 *   1. 生成时**只读 cases.json**，holdout.json 不加载、不喂给模型；
 *   2. 生成完立刻查重，与 holdout 的 4-gram Jaccard ≥ 0.3 的一律丢弃；
 *   3. 每条带 synthetic:true，报告里必须标明是合成数据。
 *
 * 用法：
 *   node test/gen-synthetic.js            # 生成
 *   node test/gen-synthetic.js --dry      # 只看提示词，不调用
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
// 项目自带 env 加载器，不依赖 dotenv
const envFile = path.join(ROOT, '.env');
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
}
const { Pangu } = require(path.join(ROOT, 'src', 'Pangu.js'));

const CASES = path.join(__dirname, 'cases.json');
const HOLDOUT = path.join(__dirname, 'holdout.json');
const OUT = path.join(__dirname, 'synthetic.json');

const ARGS = process.argv.slice(2);
const DRY = ARGS.includes('--dry');
const PER_TYPE = Number((ARGS.find(a => a.startsWith('--per=')) || '--per=25').split('=')[1]);

// 12 类诈骗在真实短信里的样子（给模型的场景提示，不是话术模板）
const TYPE_HINT = {
  '冒充公检法': '自称公安/检察院/法院/通信管理局，说老人身份证涉案、涉嫌洗钱、要配合资金核查',
  '冒充亲友': '自称子女、孙辈、亲戚、老同学，用新号码，说手机丢了/出事急需钱',
  '刷单兼职': '招募兼职刷单、点赞、做任务返佣，先给小利再让垫付',
  '贷款': '无抵押秒批、额度已下发、要先交验资费/解冻费',
  '客服退款': '自称电商/快递客服，说商品有问题或包裹丢失要理赔，让点链接或报卡号',
  '投资理财': '荐股、内幕消息、老师带单、数字人民币/虚拟币理财，晒收益',
  '中奖': '抽中大奖、积分兑奖、免费领礼品，要先付运费税费',
  '养老医保': '养老金调整、医保卡停用、社保补贴发放，让点链接或提供银行卡',
  '钓鱼链接': '冒充银行/运营商/政务，说账户异常或积分过期，附短链接',
  '索要验证码': '以各种理由要短信验证码，或称在帮老人办理业务需要口头报码',
  '屏幕共享': '指导老人下载会议软件、开屏幕共享、远程协助，说帮忙操作',
  '其他': '冒充熟人推销、假绑架、AI 换脸视频通话借钱等其余套路',
};

// 正常短信的场景，用来配负样本，避免模型把所有长短信都判成诈骗
const NORMAL_HINT = {
  '快递物流': '取件码、派送通知、驿站提醒、快递柜超时',
  '银行通知': '动账提醒、信用卡账单、理财到期、网点营业时间调整',
  '医院健康': '体检通知、复诊提醒、疫苗、社区义诊',
  '物业社区': '停水停电、电梯检修、垃圾分类、社区活动',
  '家人日常': '子女问候、接送孩子、家里买东西、让带钥匙',
  '电商订单': '发货提醒、评价邀请、优惠券到期、会员续费',
  '政务通知': '社保年审、公积金、居住证、人口普查登记',
  '招聘求职': '面试通知、入职材料、薪资发放',
};

function readJson(p) {
  const d = JSON.parse(fs.readFileSync(p, 'utf8'));
  return Array.isArray(d) ? d : (d.cases || []);
}

function grams(s, n = 4) {
  const t = String(s).replace(/\s+/g, '');
  const out = new Set();
  for (let i = 0; i + n <= t.length; i++) out.add(t.slice(i, i + n));
  return out;
}

function jaccard(a, b) {
  if (!a.size || !b.size) return 0;
  let inter = 0;
  for (const g of a) if (b.has(g)) inter++;
  return inter / (a.size + b.size - inter);
}

const SYS = '你是中文短信语料撰写员。你只输出 JSON 数组，不要任何解释、不要 markdown 代码块。';

function promptFraud(type, samples, n) {
  return `以下是若干条【${type}】类电信诈骗短信的真实样本，来自反诈样本库：

${samples.map((s, i) => `${i + 1}. ${s}`).join('\n')}

请再写 ${n} 条同一类型的诈骗短信，要求：
1. 场景：${TYPE_HINT[type] || type}。收信人是 60 岁以上老人。
2. 长度 20~80 字，像真人手机收到的短信，可带号码、链接、金额。
3. **必须换说法**：不要照抄上面的用词、句式、称呼、机构名。同一套路要用不同的话讲出来。
4. 覆盖不同档次：有的露骨（直接提转账），有的含蓄（只铺垫、先建立信任、留联系方式让对方主动联系）。
5. 不要出现"这是诈骗""请勿转账"这类提示语。

只输出 JSON 数组，例如 ["短信一","短信二"]。`;
}

function promptNormal(kind, samples, n) {
  return `以下是若干条真实的【${kind}】类正常短信，收信人是 60 岁以上老人：

${samples.map((s, i) => `${i + 1}. ${s}`).join('\n')}

请再写 ${n} 条同类的正常短信，要求：
1. 场景：${NORMAL_HINT[kind] || kind}。
2. 长度 15~70 字，真实、口语化、带具体机构或时间。
3. **不要**出现任何索要验证码、让点陌生链接、让转账、威胁恐吓的内容。
4. 不要照抄上面的句子。

只输出 JSON 数组，例如 ["短信一","短信二"]。`;
}

function parseArr(txt) {
  let s = String(txt).trim()
    .replace(/^```(?:json)?/i, '').replace(/```$/, '').trim();
  const a = s.indexOf('[');
  const b = s.lastIndexOf(']');
  if (a >= 0 && b > a) s = s.slice(a, b + 1);
  try {
    const arr = JSON.parse(s);
    if (Array.isArray(arr)) return arr.filter(x => typeof x === 'string');
  } catch (e) { /* 落到按行拆 */ }
  return s.split(/\r?\n/)
    .map(x => x.replace(/^\s*(?:[-*·]|\d+[.、)])\s*/, '').replace(/^["'“]|["'”],?$/g, '').trim())
    .filter(x => x.length >= 10);
}

async function main() {
  const casesArr = readJson(CASES);
  // 注意：holdout 只在本进程里用于**查重**，绝不进入提示词
  const holdoutArr = readJson(HOLDOUT);
  const holdoutGrams = holdoutArr.map(x => ({ g: grams(x.text), label: x.expect, text: x.text }));

  const fraud = casesArr.filter(x => x.expect === 'fraud');
  const normal = casesArr.filter(x => x.expect !== 'fraud');
  console.log(`训练侧：cases 诈骗 ${fraud.length} 条 / 正常 ${normal.length} 条；留出集 ${holdoutArr.length} 条（仅用于查重）`);

  const jobs = [];
  for (const [type] of Object.entries(TYPE_HINT)) {
    const pool = fraud.filter(x => (x.type || '其他') === type).map(x => x.text);
    if (!pool.length) { console.log(`  跳过 ${type}：cases 里没有样本`); continue; }
    jobs.push({
      label: 'fraud', type, pool,
      sys: SYS, user: promptFraud(type, pool.slice(0, 6), PER_TYPE),
    });
  }
  const npool = {};
  for (const x of normal) {
    const k = x.type || x.kind || '其他正常';
    (npool[k] = npool[k] || []).push(x.text);
  }
  const normalKinds = Object.keys(NORMAL_HINT);
  for (const kind of normalKinds) {
    // 训练集里没有对应场景时，用现有正常样本兜底当风格参考
    const pool = npool[kind] && npool[kind].length ? npool[kind] : normal.slice(0, 5).map(x => x.text);
    jobs.push({
      label: 'normal', type: kind, pool,
      sys: SYS, user: promptNormal(kind, pool.slice(0, 6), Math.round(PER_TYPE * 0.7)),
    });
  }

  if (DRY) {
    console.log(`共 ${jobs.length} 个生成任务。示例提示词：\n\n${jobs[0].user}`);
    return;
  }

  const pangu = new Pangu({ timeout: 120000 });
  if (!pangu.enabled) {
    console.error('未配置 MAAS_KEY，无法生成。');
    process.exit(1);
  }

  const kept = [];
  const seen = new Set();
  let droppedDup = 0, droppedNear = 0;

  for (const job of jobs) {
    process.stdout.write(`  ${job.label === 'fraud' ? '诈骗' : '正常'}·${job.type} ...`);
    let raw = '';
    try {
      raw = await pangu.chat(
        [{ role: 'system', content: job.sys }, { role: 'user', content: job.user }],
        { temperature: 1.0, maxTokens: 3000 }
      );
    } catch (e) {
      console.log(` 失败：${e.message}`);
      continue;
    }
    const items = parseArr(raw);
    let add = 0;
    for (const t of items) {
      const text = t.replace(/\s+/g, ' ').trim();
      if (text.length < 8 || text.length > 200) continue;
      const key = text.replace(/[^\u4e00-\u9fa5a-z0-9]/gi, '');
      if (seen.has(key)) { droppedDup++; continue; }
      const g = grams(text);
      // 与留出集查重：撞车就丢，保证评估干净
      let worst = 0;
      for (const h of holdoutGrams) {
        const j = jaccard(g, h.g);
        if (j > worst) worst = j;
        if (worst >= 0.3) break;
      }
      if (worst >= 0.3) { droppedNear++; continue; }
      seen.add(key);
      kept.push({
        text,
        expect: job.label,
        type: job.type,
        synthetic: true,
        source: 'deepseek-gen',
      });
      add++;
    }
    console.log(` 得到 ${items.length} 条，采纳 ${add} 条`);
  }

  const out = {
    generatedAt: new Date().toISOString(),
    method: 'DeepSeek 按 cases.json 已有套路换说法扩写',
    caution: '合成数据，不是真实收集样本；与 holdout 已做 4-gram 查重（阈值 0.3）',
    perType: PER_TYPE,
    dropped: { duplicate: droppedDup, nearHoldout: droppedNear },
    cases: kept,
  };
  fs.writeFileSync(OUT, JSON.stringify(out, null, 1), 'utf8');

  const fc = kept.filter(x => x.expect === 'fraud').length;
  console.log(`\n完成：共保留 ${kept.length} 条（诈骗 ${fc} / 正常 ${kept.length - fc}），` +
    `丢弃重复 ${droppedDup} 条、与留出集过近 ${droppedNear} 条`);
  console.log(`写入 ${OUT}`);
}

main().catch(e => { console.error(e); process.exit(1); });
