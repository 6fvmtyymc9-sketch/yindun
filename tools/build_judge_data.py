#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把文字样本整理成 /judge 原生两轮格式的 LoRA 训练数据（ms-swift 的 messages 格式）。

    正常样本：CGoDial 真实政务热线（text_asr）、TeleAntiFraud 正常对话、营销短信
    诈骗样本：TeleAntiFraud 诈骗对话、Telecom_Fraud_Texts_8

输出（都在 --out 目录下）：
    train.jsonl / val.jsonl     训练用，两类数量相等
    heldout_cgodial.jsonl       留出的 CGoDial 真实对话，**永远不参与训练**
    summary.json                各来源条数、长度分布、**confidence 分布、reason 唯一值数**

用法（在学校 GPU 容器里）：
    python3 ~/yindun/tools/build_judge_data.py \
        --cgodial ~/data/cgodial/cgodial_flow_dialogs.jsonl \
        --extra ~/work/taf_normal.jsonl ~/work/normal_sms.jsonl \
                ~/work/taf_fraud.jsonl ~/work/fraud_texts.jsonl \
        --out ~/work/r3/data --per-class 2500

与上一版的三处不同（2026-11-23 老师意见）：
    1. 理由按原文内容生成：从原文里抽出「冒充身份 / 索要资金 / 索要敏感信息 / 引导操作 /
       施压 / 利诱 / 保密」七类线索，用命中的具体词拼句子，不再是每类一句模板。
    2. 置信度随线索多少变化：诈骗 0.85~0.98、正常 0.03~0.25，不再写死 0.9 / 0.1。
    3. 数据自带 reason 时优先用自带的（我们只调 confidence）。

仍然保留的老处理（每条都是为了不让模型「抄近路」）：
    · 去掉「客服：」「来电人：」角色标签（线上转写本来没有说话人）
    · 长度统一截到 60~800 字，避免「长 = 正常、短 = 诈骗」
    · 第一轮场景答案一律「咨询客服」，场景不泄露标签
    · 留出集按场景分层、按文本去重，训练集出现的文本不会进留出集
"""
import argparse
import json
import os
import random
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sft_server import NATIVE_Q1_TEXT, NATIVE_Q2  # noqa: E402  与推理服务的提示词一字不差

ROLE = re.compile(r"^\s*[^：:\n]{1,6}[：:]\s*", re.M)
MIN_CHARS, MAX_CHARS = 60, 800
HELDOUT = {"gov": 200, "jiaoguan": 40, "etc": 40, "zhubao": 20}   # 共 300 通
BIZ = {"gov": "公积金、医保或社保", "jiaoguan": "户籍、证件或车驾管", "etc": "高速 ETC", "zhubao": "租房备案或公租房"}

# ---- 线索词典：理由与置信度都由它决定 -------------------------------------
CLUES = [
    ("冒充身份", ["公安局", "派出所", "检察院", "法院", "警察", "银保监", "通信管理局", "工信部",
                  "京东客服", "淘宝客服", "平台客服", "银行", "信贷", "客服", "快递", "邮政",
                  "社保局", "税务局", "保险公司", "领导", "老师", "老板", "投资公司", "经理"]),
    ("索要资金", ["转账", "汇款", "打钱", "转钱", "先转", "垫付", "充值", "借款", "交费", "付钱",
                  "手续费", "保证金", "押金", "解冻费", "税费", "定金", "刷流水"]),
    ("索要敏感信息", ["验证码", "密码", "身份证号", "身份证", "银行卡号", "卡号", "短信验证",
                      "人脸", "实名认证", "银行流水", "开户行"]),
    ("引导操作", ["下载", "点击", "链接", "加微信", "加QQ", "扫码", "屏幕共享", "远程",
                  "关注公众号", "填写信息", "验证身份"]),
    ("施压恐吓", ["马上", "立刻", "今天之内", "不然", "否则", "冻结", "逮捕", "立案", "通缉",
                  "坐牢", "处罚", "违约", "影响征信", "黑名单", "违法", "涉嫌"]),
    ("利诱", ["中奖", "奖金", "返利", "返现", "佣金", "刷单", "点赞", "做任务", "免费领",
              "高额", "低息", "无抵押", "免息", "年化", "收益率", "稳赚", "高回报", "红包"]),
    ("要求保密", ["不要告诉", "别告诉", "保密", "不能跟家人说", "先别挂", "别挂电话"]),
]

FRAUD_TPL = [
    "对方以「{id}」身份联系，{act}，{press}，具有典型的{kind}诈骗特征。",
    "通话中对方自称{id}，{act}；{press}。符合{kind}类诈骗的话术模式。",
    "对方冒充{id}取得信任后，{act}，{press}，属于{kind}诈骗。",
    "这通电话里对方以{id}名义，{act}，{press}，是常见的{kind}骗局。",
    "对方先以{id}身份铺垫，随后{act}，{press}，整体符合{kind}诈骗特征。",
    "来电者自称{id}，在通话中{act}，{press}，是{kind}类诈骗的典型手法。",
]
NORMAL_TPL = [
    "这是一通关于{biz}的正常沟通，双方只确认了{act}，没有要求转账、索要验证码或引导下载软件，不构成诈骗。",
    "通话内容围绕{biz}展开，对方说明了{act}，全程未涉及资金转账与敏感信息，属于正常业务往来。",
    "双方在讨论{biz}的具体安排（{act}），没有出现索要银行卡、验证码或要求点击陌生链接的情况。",
    "这是一次{biz}的正常联系，只涉及{act}，不涉及汇款、垫资或屏幕共享，不构成诈骗。",
    "整通电话讲的是{biz}，双方沟通了{act}，没有催促转账、没有索要身份信息，是日常事务性通话。",
    "对方就{biz}做了说明，主要确认{act}，通话里没有出现威胁、利诱或索要密码的情形。",
    "这是{biz}相关的事务沟通，{act}，不涉及资金往来，也没有引导安装软件或屏幕共享。",
    "通话双方在核对{biz}的信息（{act}），语气平和、无施压，不存在诈骗特征。",
]


def clean(text):
    t = ROLE.sub("", text or "")
    t = re.sub(r"\s+", "", t)
    return t[:MAX_CHARS]


def clues(text):
    """返回 {类别: [命中的词...]}"""
    hit = {}
    low = text.lower()
    for name, words in CLUES:
        got = [w for w in words if (w.lower() in low)]
        if got:
            hit[name] = got
    return hit


def _pick(rnd, seq, k=2):
    seq = list(dict.fromkeys(seq))
    return "、".join(seq[:k]) if seq else ""


def fraud_reason(text, kind, cl, rnd):
    idw = _pick(rnd, cl.get("冒充身份", []), 1) or (kind or "客服")
    idw = re.sub(r"^冒充", "", idw)          # 「以冒充X身份」重复，去掉前缀
    kind = (kind or "电信").replace("·", "、")
    acts = []
    if cl.get("索要资金"):
        acts.append("要求" + _pick(rnd, ["转账", "汇款", "打钱", "先转", "垫付", "付款"], 1) + "到指定账户")
    if cl.get("索要敏感信息"):
        acts.append("索取" + _pick(rnd, cl["索要敏感信息"], 2))
    if cl.get("引导操作"):
        acts.append("引导" + _pick(rnd, cl["引导操作"], 2))
    if not acts:
        acts.append("以办理业务为由要求配合操作")
    act = "，".join(acts[:2])
    press = ""
    if cl.get("施压恐吓"):
        press = "并反复强调「" + _pick(rnd, cl["施压恐吓"], 2) + "」施加压力"
    elif cl.get("利诱"):
        press = "并以「" + _pick(rnd, cl["利诱"], 2) + "」作为诱饵"
    if cl.get("要求保密"):
        press = (press + "，还要求" + _pick(rnd, cl["要求保密"], 1) + "") if press else ("还要求" + _pick(rnd, cl["要求保密"], 1))
    if not press:
        press = "全程围绕套取资金或个人信息展开"
    tpl = FRAUD_TPL[rnd.randrange(len(FRAUD_TPL))]
    return tpl.format(id=idw, act=act, press=press, kind=kind)


ACT_KWS = [("预约", "预约时间"), ("订单", "订单信息"), ("取车", "取车安排"), ("外卖", "外卖配送"),
           ("菜品", "菜品与口味"), ("营业", "营业时间"), ("收费", "收费标准"), ("地址", "收货地址"),
           ("时间", "时间安排"), ("上门", "上门服务"), ("咨询", "业务咨询"), ("办理", "办理流程"),
           ("价格", "价格与费用"), ("优惠", "优惠活动"), ("售后", "售后处理"), ("发票", "发票开具"),
           ("配送", "配送进度"), ("维修", "维修安排"), ("面试", "面试安排"), ("合同", "合同条款"),
           ("余额", "账户余额"), ("到账", "到账情况"), ("发货", "发货进度"), ("退订", "退订方式")]


def normal_reason(text, biz, cl, rnd):
    act_parts = [desc for kw, desc in ACT_KWS if kw in text]
    act = "、".join(dict.fromkeys(act_parts[:3])) or "办理流程与相关细节"
    tpl = NORMAL_TPL[rnd.randrange(len(NORMAL_TPL))]
    return tpl.format(biz=biz, act=act)


def fraud_conf(cl, rnd):
    n = len(cl)
    base = {0: 0.85, 1: 0.87, 2: 0.90, 3: 0.93, 4: 0.95, 5: 0.96, 6: 0.97}.get(n, 0.98)
    extra = min(len(sum(cl.values(), [])), 8) * 0.0025
    return round(min(0.98, base + extra), 3)


def normal_conf(cl, rnd):
    """返回要写进数据的 confidence。

    ⚠️ 语义坑（2026-11-23 踩过，别再犯）：
    服务端 /judge 的 p 是「诈骗概率」——判「是」时 p = confidence，判「否」时 p = 1 - confidence。
    所以想让正常样本的 **p** 落在 0.03~0.25，写进数据的 confidence 必须落在 **0.75~0.97**。
    第一版把 0.03~0.25 直接写成了 confidence，模型学成「判否时 confidence=0.03」，
    服务一算 p=0.97，线上口径下正常样本全部越线。
    """
    n = len(cl)
    p = {0: 0.03, 1: 0.06, 2: 0.12, 3: 0.18, 4: 0.22}.get(n, 0.25)
    p = min(0.25, p + min(len(sum(cl.values(), [])), 6) * 0.004)
    return round(1 - p, 3)


def sample(text, is_fraud, reason=None, kind=None, biz=None, rnd=None):
    cl = clues(text)
    if is_fraud:
        r = reason or fraud_reason(text, kind, cl, rnd)
        conf = fraud_conf(cl, rnd)
    else:
        biz = (biz or "日常事务")
        biz = {"营销通知短信": "手机业务通知", "营销": "手机业务通知"}.get(biz, biz)
        r = reason or normal_reason(text, biz, cl, rnd)
        conf = normal_conf(cl, rnd)
    q2 = NATIVE_Q2.replace("音频内容", "通话内容")
    r1 = json.dumps({"scene": "咨询客服", "reason": "通话双方围绕具体事项进行沟通", "confidence": 0.6},
                    ensure_ascii=False, indent=2)
    r2 = json.dumps({"reason": r, "confidence": conf, "is_fraud": bool(is_fraud)},
                    ensure_ascii=False, indent=2)
    return {"messages": [
        {"role": "user", "content": NATIVE_Q1_TEXT.format(text=text)},
        {"role": "assistant", "content": r1},
        {"role": "user", "content": q2},
        {"role": "assistant", "content": r2},
    ]}, conf, r


def classify_src(fn):
    f = os.path.basename(fn).lower()
    if "taf" in f or "teleanti" in f or "dialog" in f:
        return "teleantifraud"
    if "sms" in f or "label0" in f or "marketing" in f:
        return "sms"
    return "other"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cgodial", required=True)
    ap.add_argument("--extra", nargs="*", default=[], help="jsonl：每行 {text, label: fraud|normal, type?, reason?}")
    ap.add_argument("--out", required=True)
    ap.add_argument("--per-class", type=int, default=2500)
    ap.add_argument("--val-ratio", type=float, default=0.05)
    ap.add_argument("--no-src-reason", action="store_true",
                    help="忽略源数据自带的 reason（当自带的是按类别套的模板时用，否则理由多样性会退化成模板数）")
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    os.makedirs(a.out, exist_ok=True)

    # ---- CGoDial：只用 real，先切留出集
    real, seen_text = [], set()
    for line in open(os.path.expanduser(a.cgodial), encoding="utf-8"):
        d = json.loads(line)
        if d.get("kind") != "real":
            continue
        t = clean(d.get("text_asr") or d.get("text"))
        if len(t) >= MIN_CHARS:
            if t in seen_text:      # 文本级去重：同一段对话常有多个 id
                continue
            seen_text.add(t)
            real.append({"id": d["id"], "scene": d["scene"], "text": t})
    rnd.shuffle(real)
    held, rest, need = [], [], dict(HELDOUT)
    for d in real:
        if need.get(d["scene"], 0) > 0:
            held.append(d)
            need[d["scene"]] -= 1
        else:
            rest.append(d)
    held_ids = {d["id"] for d in held}
    rest = [d for d in rest if d["id"] not in held_ids]

    # ---- 其他来源：按文件归类
    pos, neg_src = [], defaultdict(list)
    src = Counter()
    used_text = set()
    for f in a.extra:
        kind_src = classify_src(f)
        for line in open(os.path.expanduser(f), encoding="utf-8"):
            if not line.strip():
                continue
            d = json.loads(line)
            t = clean(d.get("text"))
            if len(t) < MIN_CHARS:
                continue
            if t in used_text:      # 跨源文本去重（CGoDial 的 IVR 开场白、重复短信）
                continue
            used_text.add(t)
            lab = str(d.get("label", "")).lower()
            src_reason = None if a.no_src_reason else d.get("reason")
            if lab in ("fraud", "诈骗", "1", "true"):
                pos.append({"text": t, "type": d.get("type"), "reason": src_reason})
                src["fraud_avail:" + os.path.basename(f)] += 1
            elif lab in ("normal", "正常", "0", "false"):
                neg_src[kind_src].append({"text": t, "biz": d.get("biz") or d.get("type"),
                                          "reason": src_reason})
                src["normal_avail:" + kind_src] += 1

    n = a.per_class
    rnd.shuffle(pos)
    pos_sel = pos[:n]
    if len(pos) < n:
        print(f"[!] 诈骗样本只有 {len(pos)} 条，按 {len(pos)} 条取", file=sys.stderr)

    # ---- 正常侧：三个来源各占 1/3，不足的由其余来源补
    cgodial_pool = [{"text": d["text"], "biz": BIZ.get(d["scene"], "办事")} for d in rest]
    pools = {"teleantifraud": neg_src.get("teleantifraud", []),
             "sms": neg_src.get("sms", []),
             "other": neg_src.get("other", []),
             "cgodial": cgodial_pool}
    for k in pools:
        rnd.shuffle(pools[k])
    neg_pick = []
    quota = {k: n // 3 for k in ("teleantifraud", "sms", "cgodial")}
    for k, q in quota.items():
        neg_pick += [(k, x) for x in pools[k][:q]]
    short = n - len(neg_pick)
    if short > 0:   # 某来源不足：按长度从剩余池补齐（优先 other 与 cgodial）
        for k in ("other", "teleantifraud", "sms", "cgodial"):
            if short <= 0:
                break
            taken = {id(x) for kk, x in neg_pick if kk == k}
            cand = [x for x in pools[k] if id(x) not in taken][:short]
            neg_pick += [(k, x) for x in cand]
            short -= len(cand)
    neg_pick = neg_pick[:n]

    pos_out = [sample(d["text"], True, d.get("reason"), d.get("type"), rnd=rnd) for d in pos_sel]
    neg_out = [sample(d["text"], False, d.get("reason"), biz=d.get("biz"), rnd=rnd) for _, d in neg_pick]
    src["normal:used"] = dict(Counter(k for k, _ in neg_pick))
    src["fraud:used"] = len(pos_sel)

    data = [x[0] for x in pos_out] + [x[0] for x in neg_out]
    confs = [x[1] for x in pos_out] + [x[1] for x in neg_out]
    reasons = [x[2] for x in pos_out] + [x[2] for x in neg_out]
    rnd.shuffle(data)
    nv = max(1, int(len(data) * a.val_ratio))
    with open(os.path.join(a.out, "val.jsonl"), "w", encoding="utf-8") as fv:
        for x in data[:nv]:
            fv.write(json.dumps(x, ensure_ascii=False) + "\n")
    with open(os.path.join(a.out, "train.jsonl"), "w", encoding="utf-8") as ft:
        for x in data[nv:]:
            ft.write(json.dumps(x, ensure_ascii=False) + "\n")
    with open(os.path.join(a.out, "heldout_cgodial.jsonl"), "w", encoding="utf-8") as fh:
        for d in held:
            fh.write(json.dumps({"id": d["id"], "scene": d["scene"], "text": d["text"], "label": "normal"},
                                ensure_ascii=False) + "\n")

    # ---- 自检：留出集文本不得出现在训练/验证集里
    train_blob = "".join(x["messages"][0]["content"] for x in data)
    leak = sum(1 for d in held if d["text"] in train_blob)
    if leak:
        print(f"[!] 留出集有 {leak} 条文本泄漏进训练集，指标会虚高，先修数据再训", file=sys.stderr)
    else:
        print("[ok] 留出集 0 条泄漏进训练集", file=sys.stderr)

    # ---- answers 验收项（老师要求开训前必看的三条）
    # p = 线上那个「诈骗概率」：诈骗样本 p=confidence，正常样本 p=1-confidence
    fc = sorted(confs[:len(pos_sel)])
    nc = sorted(confs[len(pos_sel):])
    np_ = sorted(round(1 - c, 3) for c in nc)
    answers = {
        "reason_unique": len(set(reasons)),
        "reason_total": len(reasons),
        "fraud_p_min": fc[0] if fc else None,
        "fraud_p_max": fc[-1] if fc else None,
        "fraud_p_p10_p90": [fc[len(fc) // 10], fc[len(fc) * 9 // 10]] if fc else None,
        "normal_p_min": np_[0] if np_ else None,
        "normal_p_max": np_[-1] if np_ else None,
        "normal_p_p10_p90": [np_[len(np_) // 10], np_[len(np_) * 9 // 10]] if np_ else None,
        "fraud_conf_min": fc[0] if fc else None,
        "normal_conf_min": nc[0] if nc else None,
        "normal_conf_max": nc[-1] if nc else None,
        "conf_unique": len(set(confs)),
    }
    summary = {
        "train": len(data) - nv, "val": nv, "fraud": len(pos_sel), "normal": len(neg_pick),
        "heldout_cgodial": len(held), "heldout_by_scene": dict(Counter(d["scene"] for d in held)),
        "leak_check": leak, "sources": dict(src), "answers": answers,
        "chars": [MIN_CHARS, MAX_CHARS], "seed": a.seed,
    }
    json.dump(summary, open(os.path.join(a.out, "summary.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
