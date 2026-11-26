#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
银盾 · 第四轮 SFT 训练数据生成（老师 2026-11-26 方案 §3.3~3.5）
====================================================================
产出 ms-swift 可直读的 train.jsonl / val.jsonl + summary.json（九项验收）。

    python3 build_sft_data_r4.py --out ~/work/lora_r4/data

【口径总则】
  1. 标签只按**文件名前缀**判：POS-* → normal，NEG-* → fraud。
     ⚠️ `~/taf/transcripts_{pos,neg}.jsonl` 里的 `label` 字段 1400/1400 全写反了
        （POS 租车客服标成 fraud、NEG 京东退款骗局标成 normal），绝不能读。
        官方真值即目录名：NEG 组 answers=fraud 100%、POS 组 answers=normal 100%。
  2. 只走通话链路：短信不调本模型，所以训练集不掺短文本测试集（只用来做 leak_check）。
  3. 同一通录音只能出现一次（要么音频、要么转写文字）。音频池与文字池按 8-gram
     指纹互斥，防止 sft.zip 的对话文本与其音频转写撞车。

【为什么理由要这么写（v3 的教训）】
  第三轮 5,000 条只产出"5 种句式"，模型学成"关于 X 的正常沟通"这种空话，
  刷单返利样本被判成"关于刷单返利的正常沟通"——理由和结论自相矛盾。
  本轮强制：
    · 诈骗侧理由必须引用**至少一条原文里真实出现的线索**（正则命中才算）
    · 正常侧理由必须写**具体事项 + 为什么不是诈骗**，且不许出现诈骗类型名
    · 正常侧"没有索要 X"这类否定声明**只许声明原文里确实没出现过的动作**，不许撒谎
    · 每条理由带一段原文片段，保证唯一值 ≥2,000、单句重复 ≤20
"""

import argparse
import importlib.util
import json
import os
import random
import re
import sys
from collections import Counter, defaultdict

SEED = 20261127

# ------------------------------------------------------------------ 数据源
TAF_DIR = os.path.expanduser("~/taf")
AUDIO_DIR = os.path.join(TAF_DIR, "taf_audio")
POS_TR = os.path.join(TAF_DIR, "transcripts_pos.jsonl")   # 正常（真值按文件名）
NEG_TR = os.path.join(TAF_DIR, "transcripts_neg.jsonl")   # 诈骗
# 第四轮补转的 500 条 NEG（未进过任何测试集），用作诈骗侧的文字样本
NEG_TR_R4 = os.path.join(TAF_DIR, "transcripts_neg_r4.jsonl")
TAF_NORMAL_TXT = os.path.expanduser("~/work/taf_jsonl/taf_normal.jsonl")
TAF_FRAUD_TXT = os.path.expanduser("~/work/taf_jsonl/taf_fraud.jsonl")
CGODIAL = os.path.expanduser("~/data/cgodial/cgodial_flow_dialogs.jsonl")
NORMAL_SMS = os.path.expanduser("~/work/normal_sms.jsonl")
FRAUD_TEXTS = os.path.expanduser("~/work/fraud_texts.jsonl")

# 测试集，一律不许进训练
TAF200_TR = os.path.expanduser("~/taf200/transcripts_taf200.jsonl")
HOLDOUT_TXT = os.path.expanduser("~/work/lora1123c/data/heldout_cgodial.jsonl")

# ------------------------------------------------------------------ 数量规格（计划 3.3）
N_NORMAL_AUDIO = 1000       # 其中客服类 ≥500
N_NORMAL_TAF_TXT = 400
N_NORMAL_CGODIAL = 600
N_NORMAL_SMS = 500
N_FRAUD_AUDIO = 1400
N_FRAUD_TAF_TXT = 500
N_FRAUD_TEXTS = 600
N_VAL = 250

# ------------------------------------------------------------------ 问句：直接 import，不许手抄
def load_prompts():
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "sft_server.py")
    spec = importlib.util.spec_from_file_location("_sft_server_prompts", path)
    if spec is None or spec.loader is None:
        raise SystemExit("[X] 找不到 sft_server.py，无法取得原生问句")
    mod = importlib.util.module_from_spec(spec)
    # sft_server 模块级只有标准库 import 和常量，末尾有 __main__ 守卫，import 无副作用
    spec.loader.exec_module(mod)
    return mod.NATIVE_Q1, mod.NATIVE_Q1_TEXT, mod.NATIVE_Q2


# ------------------------------------------------------------------ 场景识别
# 官方 7 类 scene：订餐服务、咨询客服、预约服务、交通咨询、日常购物、打车服务、外卖服务
TOPICS = [
    # (事项名, 官方 scene, 关键词正则, 关键词列表)
    ("外卖订餐", "外卖服务", r"外卖|饿了么|美团|订餐|点餐|菜品|送餐|骑手|配送员",
     ["外卖", "订餐", "点餐", "菜品", "送餐", "骑手", "配送"]),
    ("餐饮预约", "订餐服务", r"预订|包间|几位|到店|餐位|用餐",
     ["预订", "包间", "到店", "餐位", "用餐"]),
    ("快递收寄", "日常购物", r"快递|包裹|物流|派送|签收|取件|驿站|运单",
     ["快递", "包裹", "物流", "派送", "签收", "取件"]),
    ("商品售后", "日常购物", r"售后|退货|换货|退款|质保|补发",
     ["售后", "退货", "换货", "退款", "补发"]),
    ("网约车出行", "打车服务", r"打车|滴滴|网约车|司机|接单|行程|起步价|派车",
     ["打车", "网约车", "司机", "行程", "派车"]),
    ("车辆租赁", "交通咨询", r"租车|取车|还车|行驶证|驾驶证|车型",
     ["租车", "取车", "还车", "车型", "驾驶证"]),
    ("交通票务", "交通咨询", r"车票|机票|班次|改签|退票|航班",
     ["车票", "机票", "班次", "改签", "航班"]),
    ("家电维修", "预约服务", r"维修|师傅|上门|配件|换件|检修|空调|水管",
     ["维修", "师傅", "上门", "配件", "检修"]),
    ("房屋租赁", "咨询客服", r"租房|看房|房租|押一付三|中介|房源|房东",
     ["租房", "看房", "房租", "房源", "房东"]),
    ("物业缴费", "咨询客服", r"物业|水费|电费|停车费|缴费|供暖|门禁",
     ["物业", "水费", "电费", "停车费", "缴费"]),
    ("积分权益", "日常购物", r"积分|兑换|会员|权益|商城|优惠券",
     ["积分", "兑换", "会员", "权益", "优惠券"]),
    ("银行账户服务", "咨询客服", r"银行|储蓄卡|信用卡|开户|销户|余额|柜台",
     ["银行", "账户", "信用卡", "开户", "余额", "柜台"]),
    ("政务办事", "咨询客服", r"公积金|医保|社保|政务|办事|窗口|材料|证明",
     ["公积金", "医保", "社保", "办事", "材料"]),
    ("预约挂号", "预约服务", r"挂号|门诊|科室|就诊",
     ["挂号", "门诊", "科室", "就诊"]),
]
DEFAULT_TOPIC = ("业务办理", "咨询客服", ["业务", "办理"])

# 客服类事项：计划要求正常侧客服类音频 ≥500
SERVICE_TOPICS = {t[0] for t in TOPICS} - {"政务办事"}


def detect_topic(text):
    for name, scene, pat, words in TOPICS:
        if re.search(pat, text):
            return name, scene, words
    return DEFAULT_TOPIC


# ------------------------------------------------------------------ 线索提取（诈骗侧理由必须引用真实线索）
CLUES = [
    ("要求下载 APP 或安装远程软件", r"下载|安装|装个|装一个|软件|APP|app|远程控制"),
    ("要求添加微信、QQ 等私人联系方式", r"微信|加好友|加我|QQ|qq"),
    ("索要短信验证码", r"验证码"),
    ("索要银行卡号或密码", r"密码|卡号|银行卡号|信用卡号"),
    ("要求向指定账户转账汇款", r"转账|汇款|打到|转到|转给|转入|汇到"),
    ("以保证金、手续费、解冻费为由要钱", r"保证金|押金|手续费|解冻|工本费|审核费|激活费"),
    ("冒充公安、检察院、法院办案", r"公安|警察|法院|检察院|通缉|洗钱|涉案|拘留|传票|资金清查"),
    ("以刷单返利、做任务为诱饵", r"刷单|返利|佣金|做任务|点赞|垫付"),
    ("以中奖、领奖为诱饵", r"中奖|抽奖|奖金|二等奖|领奖|幸运用户"),
    ("以退款理赔为由引导操作", r"退款|理赔|赔付|退货|双倍赔"),
    ("冒充熟人借钱或代付", r"借钱|周转|代付|代扣|我换号|新号码|我是你"),
    ("以贷款提额、征信修复为名", r"贷款|额度|提额|征信|放款"),
    ("要求共享屏幕或做账户验证", r"共享屏幕|屏幕共享|远程协助|账户验证"),
]

# 正常侧理由里禁止出现的**诈骗类型名**（计划 3.4 第 3 条）
# 只禁类型名，不禁"验证码/转账"这类中立词——正常理由写"没有索要验证码"是合法的
FRAUD_WORDS = ["刷单", "返利", "安全账户", "公检法", "洗钱", "通缉",
               "资金清查", "涉案", "诈骗", "骗局", "骗子"]

# 敏感动作：正常样本里**实际命中**的，不许声明"没有"（不许撒谎）
SENS_ACTS = [
    ("验证码", r"验证码"),
    ("银行卡或密码", r"密码|卡号|银行卡"),
    ("转账汇款", r"转账|汇款|转给|打入"),
    ("陌生软件", r"下载|安装|软件|APP|app"),
    ("私人联系方式", r"微信|加好友|加我|QQ"),
    ("各类费用", r"保证金|押金|手续费|预付"),
]
SENS_ACTS_ORDER = [n for n, _ in SENS_ACTS]


def detect_clues(text):
    out = []
    for name, pat in CLUES:
        m = re.search(pat, text)
        if m:
            out.append((name, m.group(0)))
    return out


def hit_acts(text):
    return {name for name, pat in SENS_ACTS if re.search(pat, text)}


# ------------------------------------------------------------------ 原文片段（保证理由唯一）
SPLIT_RE = re.compile(r"[。！？!?\n\r]+")
CLAUSE_RE = re.compile(r"[，,；;]")


def snippet(text, prefer_words=(), width=24, avoid=(), rng=None):
    """从原文抓一段短句当依据。
    优先挑含场景词的句子；候选有多个时**随机取一个**（总取第一句会让模板化的
    政务对话/营销短信撞车，v3 就是栽在理由重复上）。抓不到就取最长的前几段。
    avoid 里的词（诈骗类型名）一旦出现在片段里就跳过——正常侧理由引用了就违规。

    截取窗口的起点也随机偏移：CGoDial 这类政务对话开场白几乎逐字相同，
    固定取前 24 字会让上千条理由指向同一句。"""
    def clean(p):
        return p.strip(" ，,、：:")

    def ok(p):
        return not any(w in p for w in avoid)

    def cut(p):
        p = clean(p)
        if len(p) <= width:
            return p
        start = rng.randint(0, min(len(p) - width, 10)) if rng else 0
        return p[start:start + width]

    pick_one = rng.choice if rng else (lambda xs: xs[0])

    parts = [clean(p) for p in SPLIT_RE.split(text) if len(clean(p)) > 6 and ok(clean(p))]
    if not parts:
        return clean(text)[:width]

    cands = [p for p in parts if any(w in p for w in prefer_words)]
    if cands:
        return cut(pick_one(cands))

    # 再用逗号切一次，找含场景词的短句
    sub = []
    for p in parts[:10]:
        for c in CLAUSE_RE.split(p):
            c = clean(c)
            if len(c) > 6 and ok(c) and any(w in c for w in prefer_words):
                sub.append(c)
    if sub:
        return cut(pick_one(sub))

    return cut(pick_one(sorted(parts[:8], key=len)[-4:]))


# ------------------------------------------------------------------ 诈骗侧理由模板
FRAUD_TPL = [
    "通话中出现「{snip}」等表述，{clue}，符合{kind}的典型话术结构",
    "对方以「{snip}」为由推进流程，进而{clue}，这是{kind}的常见套路",
    "从头到尾围绕「{snip}」做铺垫，核心动作是{clue}，属于{kind}",
    "「{snip}」之后紧跟{clue}，话术节奏与{kind}高度一致",
    "以「{snip}」建立由头，再用{clue}完成关键一步，是{kind}的常见手法",
    "先抛出「{snip}」制造紧迫感，随后{clue}，特征上归入{kind}",
    "「{snip}」这一说法本身经不起核实，配合{clue}，可判定为{kind}",
    "整通电话的落点是{clue}，前面对「{snip}」的描述都是为了这一步，属{kind}",
    "对方反复强调「{snip}」，并要求{clue}，行为模式指向{kind}",
    "以「{snip}」取得信任后提出{clue}，顺序符合{kind}的固定剧本",
    "「{snip}」只是引子，真正目的是{clue}，应归入{kind}",
    "通话中{clue}，并用「{snip}」解释这么做的理由，与{kind}的话术吻合",
    "对方借「{snip}」把话题引向{clue}，这是{kind}的惯用路径",
    "整段对话围绕「{snip}」反复施压，最终落到{clue}，{kind}特征明显",
]

# ------------------------------------------------------------------ 正常侧理由模板
# 结构：{topic} 具体事项 + {snip} 原文依据 + {neg} 真实未出现的敏感动作
NORMAL_TPL = [
    "双方就{topic}的具体安排做了确认，提到「{snip}」，内容限于流程和细节，{neg}，属于常规业务沟通",
    "整通电话围绕{topic}展开，「{snip}」这类说明是在交代办理方式，{neg}，没有可疑之处",
    "{topic}的正常对接：对方说明了「{snip}」，涉及的是时间、方式与费用明细，{neg}",
    "这是一次{topic}事务处理，「{snip}」只是操作指引，双方信息对等，{neg}",
    "通话中双方核对{topic}相关信息，如「{snip}」，这些都能在官方渠道自行核实，{neg}",
    "{topic}的例行沟通，对方交代「{snip}」，除此之外没有提出额外要求，{neg}",
    "内容属于{topic}范畴，「{snip}」是对既定安排的复述，{neg}，无需警惕",
    "对方在{topic}事项上给出了明确说明（「{snip}」），流程公开透明，{neg}",
    "从「{snip}」可以看出这是{topic}的日常办理，诉求具体、边界清楚，{neg}",
    "典型{topic}来电：「{snip}」之后只做了进度告知，{neg}",
    "{topic}场景下的信息同步，提到「{snip}」，所有动作都在原渠道内完成，{neg}",
    "通话目的是协调{topic}，「{snip}」说明双方在确认已知事项，{neg}",
    "这通电话是{topic}的后续跟进，「{snip}」属于正常的服务提醒，{neg}",
    "{topic}过程中对方确认「{snip}」，表述与实际情况一致，{neg}",
]

# 敏感动作的"没有出现"说法（只用于原文确实没出现的动作）
SENS_NEG_TEXT = {
    "验证码": "没有索要短信验证码",
    "银行卡或密码": "没有索要银行卡号或密码",
    "转账汇款": "没有要求向陌生账户转款",
    "陌生软件": "没有要求下载来路不明的软件",
    "私人联系方式": "没有要求添加私人联系方式",
    "各类费用": "没有临时加收任何费用",
}
# 敏感动作**确实出现**时的解释（成对难例：同样的词，正常场景）
SENS_POS_TEXT = {
    "验证码": "文中的验证码是本人操作时收到的登录校验，不是向对方口述",
    "银行卡或密码": "核对卡号是为了确认业务对象，没有索取密码",
    "转账汇款": "涉及的款项走的是本人已知的官方缴费或退款渠道",
    "陌生软件": "提到的软件是官方渠道可查的正规应用，并非远程控制工具",
    "私人联系方式": "加联系方式用于业务跟进，没有借机索要账户信息",
    "各类费用": "提到的费用是业务本身的明码标价，不是临时加收的名目",
}
# 全无敏感动作时的通用说法（多种句式，保证多样性）
NEG_TPL_CLEAN = [
    "全程未涉及资金转移、账户核验或私下操作",
    "没有任何索款、索要证件或引导脱离官方渠道的环节",
    "对话中不存在资金往来要求，也没有身份核验压力",
    "未提出与钱款或账户相关的任何要求",
]


def build_normal_reason(rng, text, topic, words):
    snip = snippet(text, prefer_words=words, avoid=FRAUD_WORDS, rng=rng)
    acts = hit_acts(text)
    negatives = [SENS_NEG_TEXT[a] for a in SENS_ACTS_ORDER if a in SENS_NEG_TEXT and a not in acts]
    positives = [SENS_POS_TEXT[a] for a in SENS_ACTS_ORDER if a in acts and a in SENS_POS_TEXT]
    rng.shuffle(negatives)
    # 否定项取 1~2 条，别每次都把同样两条拼在句尾（那样整句只剩前半段在变）
    k = min(len(negatives), rng.choice([1, 2]))
    pieces = positives + negatives[:k]
    neg = "；".join(p for p in pieces if p) if pieces else rng.choice(NEG_TPL_CLEAN)
    reason = rng.choice(NORMAL_TPL).format(topic=topic, snip=snip, neg=neg)
    if any(w in reason for w in FRAUD_WORDS):
        # 原文片段里带了诈骗类型名（"别被骗"之类），换成中性指代再生成一次
        reason = rng.choice(NORMAL_TPL).format(topic=topic, snip="上述事项", neg=neg)
    return reason


# 第一轮（场景分析）的 reason：不能写死，否则几千条一模一样。
# 第一轮不参与 loss（loss_scale last_round），但数据太单调会带偏模仿倾向，
# 且计划 3.4 的多样性验收是**两轮理由一起统计**的。
# 每条都必须带 {topic} 和 {snip}：只带 topic 时 6 模板×15 场景=90 种，
# 摊到 5,000 条上每句重复 40 次，过不了"单句 ≤20"。
FIRST_REASON_TPL = [
    "通话双方围绕{topic}进行沟通，涉及「{snip}」",
    "从内容判断属于{topic}咨询，提到「{snip}」",
    "对方就{topic}的具体事项做确认，如「{snip}」",
    "通话主题为{topic}，双方在核对「{snip}」",
    "这通电话是在处理{topic}相关事务，内容涉及「{snip}」",
    "内容集中在{topic}的流程说明上，出现「{snip}」",
    "{topic}类来电，对方说明「{snip}」",
    "通话属于{topic}，依据是「{snip}」",
]
FRAUD_FIRST_TPL = [
    "对方以{topic}为由推进事项，涉及账户或款项操作，如「{snip}」",
    "通话围绕{topic}展开，但夹杂资金相关要求（「{snip}」）",
    "表面是{topic}，实际提及账户与转账类操作，出现「{snip}」",
    "对方借{topic}名义提出资金方面的要求，例如「{snip}」",
    "通话主题标注为{topic}，同时出现账户操作指引（「{snip}」）",
    "以{topic}开场，随后转向资金话题，如「{snip}」",
    "{topic}场景下出现「{snip}」，并引出账户相关操作",
    "通话内容与{topic}有关，关键句是「{snip}」",
]


def build_first_reason(rng, text, topic, words, is_fraud):
    if is_fraud:
        clues = detect_clues(text)
        snip = snippet(text, prefer_words=[c[1] for c in clues], rng=rng)
        return rng.choice(FRAUD_FIRST_TPL).format(topic=topic, snip=snip)
    snip = snippet(text, prefer_words=words, avoid=FRAUD_WORDS, rng=rng)
    return rng.choice(FIRST_REASON_TPL).format(topic=topic, snip=snip)


# 一条线索都没命中时的兜底措辞。必须给，不能让 {clue} 空着：
# 空槽位会生成「整通电话的落点是，前面对…」这种残句，而残句因为结构固定反而成了
# 训练集里最高频的句式（每种 18~27 次，比正常侧任何句式都集中），
# 模型于是塌缩成复读这一句 —— 第一轮训练 35% 误报的直接来源。
FALLBACK_CLUE = [
    "涉及资金或账户信息的处理，需要进一步核实",
    "围绕钱款事项提出具体操作要求",
    "把话题引向账户或款项操作",
    "要求配合处理与账户相关的具体事项",
    "以办理业务为由推进资金方面的操作",
]


def build_fraud_reason(rng, text, clues):
    clue_txt = "、".join(c[0] for c in clues[:3]) if clues else rng.choice(FALLBACK_CLUE)
    snip = snippet(text, prefer_words=[c[1] for c in clues], rng=rng)
    kind = rng.choice(["此类诈骗", "这类诈骗", "该类型诈骗"])
    return rng.choice(FRAUD_TPL).format(snip=snip, clue=clue_txt, kind=kind)


# ------------------------------------------------------------------ confidence 分档（计划 3.4）
def fraud_conf(rng, n_clues):
    """诈骗 0.88 + 0.02×线索数，上限 0.98，±0.01。p = conf。
    无线索时用 0.88 而不是 max(...,1) 的 0.90 —— 原来所有无线索样本都挤在 0.90，
    成了训练集里最高频的 confidence 取值（530 条），把置信度教成了常数。"""
    v = 0.88 + 0.02 * min(max(n_clues, 0), 5) + rng.uniform(-0.01, 0.01)
    return round(min(max(v, 0.88), 0.98), 4)


def normal_conf(rng, text):
    """正常：普通 0.92~0.97；含退款/补发/积分等敏感词的 0.82~0.90。
    p = 1 − conf，两档分别落 p 0.03~0.08 与 0.10~0.18。"""
    if re.search(r"退款|退货|理赔|赔付|补发|积分|保证金|押金|手续费|冻结|解冻", text):
        return round(rng.uniform(0.82, 0.90), 4)
    return round(rng.uniform(0.92, 0.97), 4)


# ------------------------------------------------------------------ 文本工具
def norm(text):
    return re.sub(r"[^\u4e00-\u9fa50-9a-zA-Z]", "", text)


def grams(text, n=8):
    t = norm(text)
    return {t[i:i + n] for i in range(max(0, len(t) - n + 1))}


class GramIndex:
    """n-gram 倒排索引：查"这段文字与语料库是否高度重叠"从 O(N) 降到 O(len)。"""

    def __init__(self, corpus, n=8):
        self.n = n
        self.inv = defaultdict(set)
        self.sizes = []
        for i, t in enumerate(corpus):
            g = grams(t, n)
            self.sizes.append(len(g))
            for x in g:
                self.inv[x].add(i)

    def max_overlap(self, text):
        """返回 (最大覆盖比例, 命中的语料下标)。"""
        g = grams(text, self.n)
        if not g:
            return 0.0, -1
        cnt = defaultdict(int)
        for x in g:
            for i in self.inv.get(x, ()):
                cnt[i] += 1
        if not cnt:
            return 0.0, -1
        i = max(cnt, key=lambda k: cnt[k] / max(1, self.sizes[k]))
        return cnt[i] / max(1, self.sizes[i]), i


# ------------------------------------------------------------------ 读数据
def read_jsonl(path):
    out = []
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


def load_audio_pool():
    """音频池。标签按文件名判——POS=正常、NEG=诈骗，不读 label 字段（它是反的）。"""
    pool = []
    for path, lab, prefix in ((POS_TR, "normal", "POS-"), (NEG_TR, "fraud", "NEG-")):
        for r in read_jsonl(path):
            fn = r["file"]
            if not fn.startswith(prefix):
                raise SystemExit(f"[X] {fn} 在 {os.path.basename(path)} 里但没有 {prefix} 前缀，中止")
            if r.get("label") == lab:
                raise SystemExit(f"[X] {fn} 的 label 字段等于真值，与已知的"
                                 f"'字段全反'结论不符，先复查再跑")
            ap = os.path.join(AUDIO_DIR, fn)
            if not os.path.exists(ap):
                continue
            pool.append({"file": fn, "path": ap, "label": lab,
                         "text": r["text"], "durSec": r.get("durSec", 0)})
    return pool


# ------------------------------------------------------------------ 样本组装
def _ans(scene, reason1, conf1):
    return json.dumps({"scene": scene, "reason": reason1, "confidence": conf1},
                      ensure_ascii=False, indent=2)


def _ans2(reason2, conf2, is_fraud):
    return json.dumps({"reason": reason2, "confidence": conf2, "is_fraud": is_fraud},
                      ensure_ascii=False, indent=2)


def make_audio_sample(path, scene, r1, c1, r2, c2, is_fraud, q1, q2):
    return {"messages": [
        {"role": "user", "content": "<audio>" + q1},
        {"role": "assistant", "content": _ans(scene, r1, c1)},
        {"role": "user", "content": q2},
        {"role": "assistant", "content": _ans2(r2, c2, is_fraud)},
    ], "audios": [path]}


def make_text_sample(text, scene, r1, c1, r2, c2, is_fraud, q1_text, q2_text):
    return {"messages": [
        {"role": "user", "content": q1_text.format(text=text)},
        {"role": "assistant", "content": _ans(scene, r1, c1)},
        {"role": "user", "content": q2_text},
        {"role": "assistant", "content": _ans2(r2, c2, is_fraud)},
    ]}


# ------------------------------------------------------------------ 主流程
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.expanduser("~/work/lora_r4/data"))
    ap.add_argument("--dry", action="store_true", help="只统计不写盘")
    args = ap.parse_args()

    rng = random.Random(SEED)
    q1, q1_text, q2 = load_prompts()
    q2_text = q2.replace("音频内容", "通话内容")
    os.makedirs(args.out, exist_ok=True)

    audio_pool = load_audio_pool()
    pos_pool = [a for a in audio_pool if a["label"] == "normal"]
    neg_pool = [a for a in audio_pool if a["label"] == "fraud"]
    print(f"[i] 音频池 正常 {len(pos_pool)} / 诈骗 {len(neg_pool)}")

    # ---- 正常音频：客服类优先 ≥500，含敏感词难例全收，其余补日常
    def is_svc(a):
        return detect_topic(a["text"])[0] in SERVICE_TOPICS

    def is_sens(a):
        return bool(re.search(r"下载|安装|软件|APP|app|微信|保证金|押金|手续费|退款|积分", a["text"]))

    pos_pool.sort(key=lambda a: a["file"])
    svc_list = [a for a in pos_pool if is_svc(a)]
    sens_list = [a for a in pos_pool if is_sens(a) and not is_svc(a)]
    rest = [a for a in pos_pool if not is_svc(a) and not is_sens(a)]
    for lst in (svc_list, sens_list, rest):
        rng.shuffle(lst)

    n_svc = min(len(svc_list), 620)
    normal_audio = svc_list[:n_svc]
    normal_audio += sens_list[:max(0, N_NORMAL_AUDIO - len(normal_audio))]
    normal_audio += rest[:max(0, N_NORMAL_AUDIO - len(normal_audio))]
    if len(normal_audio) < N_NORMAL_AUDIO:
        normal_audio += svc_list[n_svc:n_svc + (N_NORMAL_AUDIO - len(normal_audio))]
    normal_audio = normal_audio[:N_NORMAL_AUDIO]
    print(f"[i] 正常音频 {len(normal_audio)}（客服类 {sum(1 for a in normal_audio if is_svc(a))}，"
          f"含敏感词 {sum(1 for a in normal_audio if is_sens(a))}）")

    # ---- 诈骗音频：NEG 池按文件名稳定排序后取前 1400
    neg_pool.sort(key=lambda a: a["file"])
    fraud_audio = neg_pool[:N_FRAUD_AUDIO]
    print(f"[i] 诈骗音频 {len(fraud_audio)}")

    # ---- 文字侧去重：指纹只对**被选用的音频样本**建，不对全池建
    #      否则"正常文字 400 条"取的是 POS 池剩余转写，会与自己那条音频的转写 100% 撞上
    audio_idx = GramIndex([a["text"] for a in normal_audio + fraud_audio])
    leak_corpus = []
    leak_files = set()
    for r in read_jsonl(TAF200_TR):
        leak_files.add(r["file"])
        leak_corpus.append(r["text"])
    for r in read_jsonl(HOLDOUT_TXT):
        if r.get("text"):
            leak_corpus.append(r["text"])
    leak_idx = GramIndex(leak_corpus)

    def usable(t):
        if len(t) < 30:
            return False
        if audio_idx.max_overlap(t)[0] > 0.5:
            return False
        if leak_idx.max_overlap(t)[0] > 0.5:
            return False
        return True

    seen = set()          # 完全相同的文本只用一次（CGoDial 里有整段重复的样本）

    def pick(rows, n):
        out = []
        for r in rows:
            t = (r.get("text") or "").strip()
            if not usable(t):
                continue
            k = norm(t)
            if k in seen:
                continue
            seen.add(k)
            out.append(t)
            if len(out) >= n:
                break
        return out

    # ⚠️ 计划 §3.3 写的"TeleAntiFraud 另一批文字 400"实际不存在：
    #    实测 taf_fraud.jsonl 的 1,393 条与 NEG 音频转写**归一化后完全相同**，
    #    taf_normal 同理——TAF 的对话文本就是其音频的转写稿，是同一份内容的两种形态。
    #    所以正常的 400 条文字取 POS 池里**没被选作音频**的剩余转写，
    #    诈骗的 500 条取本轮新转写的 500 条 NEG（与音频池、与所有测试集都不重叠）。
    chosen_files = {x["file"] for x in normal_audio}
    rest_pos = [a for a in pos_pool if a["file"] not in chosen_files]
    rng.shuffle(rest_pos)
    normal_taf_txt = pick(rest_pos, N_NORMAL_TAF_TXT)

    cgodial = [r for r in read_jsonl(CGODIAL) if len(r.get("text") or "") >= 60]
    sms = read_jsonl(NORMAL_SMS)
    ft = read_jsonl(FRAUD_TEXTS)
    for lst in (cgodial, sms, ft):
        rng.shuffle(lst)

    normal_cgodial = pick(cgodial, N_NORMAL_CGODIAL)
    normal_sms = pick(sms, N_NORMAL_SMS)
    fraud_taf_txt = pick(read_jsonl(NEG_TR_R4), N_FRAUD_TAF_TXT)

    by_type = defaultdict(list)
    for r in ft:
        by_type[r.get("type", "其他")].append(r)
    fraud_texts = []
    per = N_FRAUD_TEXTS // max(1, len(by_type))
    for typ, rows in by_type.items():
        fraud_texts += pick(rows, per)
    if len(fraud_texts) < N_FRAUD_TEXTS:
        for typ, rows in by_type.items():
            left = N_FRAUD_TEXTS - len(fraud_texts)
            if left <= 0:
                break
            fraud_texts += pick(rows[per:], left)

    print(f"[i] 文字池 正常 taf {len(normal_taf_txt)} / cgodial {len(normal_cgodial)} / sms {len(normal_sms)}"
          f"；诈骗 taf {len(fraud_taf_txt)} / texts8 {len(fraud_texts)}（类型 {list(by_type)}）")

    # ---- 组装
    samples = []

    for a in normal_audio:
        topic, scene, words = detect_topic(a["text"])
        samples.append(("normal", "audio", make_audio_sample(
            a["path"], scene, build_first_reason(rng, a["text"], topic, words, False),
            round(rng.uniform(0.6, 0.75), 2),
            build_normal_reason(rng, a["text"], topic, words),
            normal_conf(rng, a["text"]), False, q1, q2), a["file"], a["text"]))

    for a in fraud_audio:
        clues = detect_clues(a["text"])
        topic, scene, _ = detect_topic(a["text"])
        samples.append(("fraud", "audio", make_audio_sample(
            a["path"], scene, build_first_reason(rng, a["text"], topic, (), True),
            round(rng.uniform(0.6, 0.75), 2),
            build_fraud_reason(rng, a["text"], clues),
            fraud_conf(rng, len(clues)), True, q1, q2), a["file"], a["text"]))

    def add_text(rows, label):
        for t in rows:
            topic, scene, words = detect_topic(t)
            if label == "normal":
                r2 = build_normal_reason(rng, t, topic, words)
                c2 = normal_conf(rng, t)
            else:
                clues = detect_clues(t)
                r2 = build_fraud_reason(rng, t, clues)
                c2 = fraud_conf(rng, len(clues))
            r1 = build_first_reason(rng, t, topic, words, label == "fraud")
            samples.append((label, "text", make_text_sample(
                t, scene, r1, round(rng.uniform(0.6, 0.75), 2),
                r2, c2, label == "fraud", q1_text, q2_text), None, t))

    add_text(normal_taf_txt, "normal")
    add_text(normal_cgodial, "normal")
    add_text(normal_sms, "normal")
    add_text(fraud_taf_txt, "fraud")
    add_text(fraud_texts, "fraud")
    print(f"[i] 样本合计 {len(samples)}")

    # ---- 分层拆分
    by_key = defaultdict(list)
    for s in samples:
        by_key[(s[0], s[1])].append(s)
    train, val = [], []
    for key, rows in by_key.items():
        rng.shuffle(rows)
        nv = int(round(len(rows) * N_VAL / max(1, len(samples))))
        val += rows[:nv]
        train += rows[nv:]
    while len(val) < N_VAL and train:
        val.append(train.pop())
    rng.shuffle(train)
    rng.shuffle(val)

    if not args.dry:
        with open(os.path.join(args.out, "train.jsonl"), "w", encoding="utf-8") as f:
            for _, _, smp, _, _ in train:
                f.write(json.dumps(smp, ensure_ascii=False) + "\n")
        with open(os.path.join(args.out, "val.jsonl"), "w", encoding="utf-8") as f:
            for _, _, smp, _, _ in val:
                f.write(json.dumps(smp, ensure_ascii=False) + "\n")

    summary = check(samples, train, val, leak_files, audio_idx, leak_idx, is_svc)
    if not args.dry:
        with open(os.path.join(args.out, "summary.json"), "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)

    print(json.dumps(summary["checks"], ensure_ascii=False, indent=2))
    ok = all(c["pass"] for c in summary["checks"].values())
    print(f"\n{'✅ 九项全过' if ok else '❌ 有项未过，不能开训'}")
    return 0 if ok else 1


def check(samples, train, val, leak_files, audio_idx, leak_idx, is_svc):
    checks = {}
    total = len(train) + len(val)
    n_f = sum(1 for s in samples if s[0] == "fraud")
    n_n = sum(1 for s in samples if s[0] == "normal")

    checks["总数_正负"] = {"pass": total >= 4900 and abs(n_f - n_n) <= 20,
                          "value": f"{total} 条，诈骗 {n_f} / 正常 {n_n}",
                          "require": "≈5,000，各 2,500"}

    audio_rows = [s for s in samples if s[1] == "audio"]
    missing = [s[2]["audios"][0] for s in audio_rows if not os.path.exists(s[2]["audios"][0])]
    checks["音频样本"] = {"pass": len(audio_rows) >= 2400 and not missing,
                         "value": f"{len(audio_rows)} 条，缺文件 {len(missing)}",
                         "require": "≥2,400 且文件全部存在"}

    svc_audio = sum(1 for s in audio_rows if s[0] == "normal" and is_svc({"text": s[4]}))
    checks["正常侧客服类音频"] = {"pass": svc_audio >= 500, "value": svc_audio, "require": "≥500"}

    hit_files = [s[4] for s in audio_rows if s[4] in leak_files]
    hit_text = sum(1 for s in samples if leak_idx.max_overlap(s[4])[0] > 0.5)
    checks["leak_check"] = {"pass": not hit_files and hit_text == 0,
                            "value": f"文件名命中 {len(hit_files)}，文本命中 {hit_text}", "require": "0"}

    both = sum(1 for s in samples if s[1] == "text" and audio_idx.max_overlap(s[4])[0] > 0.5)
    checks["同一录音音频与文字重复"] = {"pass": both == 0, "value": both, "require": "0"}

    reasons = []
    for s in samples:
        reasons.append(json.loads(s[2]["messages"][1]["content"])["reason"])
        reasons.append(json.loads(s[2]["messages"][3]["content"])["reason"])
    cnt = Counter(reasons)
    checks["理由多样性"] = {"pass": len(cnt) >= 2000 and cnt.most_common(1)[0][1] <= 20,
                            "value": f"唯一值 {len(cnt)}，最多重复 {cnt.most_common(1)[0][1]} 次",
                            "require": "≥2,000 且单句 ≤20"}

    bad = sum(1 for s in samples if s[0] == "normal"
              and any(w in json.loads(s[2]["messages"][3]["content"])["reason"] for w in FRAUD_WORDS))
    checks["正常理由含诈骗类型名"] = {"pass": bad == 0, "value": bad, "require": "0"}

    fp, np_ = [], []
    for s in samples:
        v = json.loads(s[2]["messages"][3]["content"])
        p = v["confidence"] if v["is_fraud"] else 1 - v["confidence"]
        (fp if s[0] == "fraud" else np_).append(p)
    checks["诈骗_p区间"] = {"pass": min(fp) >= 0.87 and max(fp) <= 0.99,
                             "value": f"{min(fp):.3f} ~ {max(fp):.3f}", "require": "0.88 ~ 0.98"}
    checks["正常_p区间"] = {"pass": min(np_) >= 0.02 and max(np_) <= 0.19,
                             "value": f"{min(np_):.3f} ~ {max(np_):.3f}", "require": "0.03 ~ 0.18"}

    src = Counter(f"{s[0]}_{s[1]}" for s in samples)
    return {"total": total, "train": len(train), "val": len(val),
            "sources": dict(src), "checks": checks}


if __name__ == "__main__":
    sys.exit(main())
