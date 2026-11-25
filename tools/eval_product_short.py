# -*- coding: utf-8 -*-
"""B 组 50 条真短信，走线上真实产品链路 /api/report（dryRun=1，不写库）。
目的：拿到"产品到底误报多少"，而不是单模型误报多少。
串行 + 0.6s 间隔：并发 2 会触发中间层 403，本机实测。
"""
import csv
import json
import time
import urllib.error
import urllib.request

URL = "http://123.249.25.139:8080/api/report"
SRC = "A组_原始_20261125.csv"
OUT = "product_a50_result.jsonl"


def call(row, tries=3):
    body = {"text": row["正文"], "dryRun": 1}
    last = None
    for k in range(tries):
        req = urllib.request.Request(URL, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json",
                                              "User-Agent": "Mozilla/5.0"})
        t0 = time.time()
        try:
            return json.loads(urllib.request.urlopen(req, timeout=120).read()), time.time() - t0
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            time.sleep(2 * (k + 1))          # 403 多半是短时高频，退避后重试
        except Exception as e:
            last = f"{type(e).__name__}: {e}"
            time.sleep(2 * (k + 1))
    return {"ok": False, "err": last}, 0.0


rows = list(csv.DictReader(open(SRC, encoding="utf-8-sig")))
print(f"共 {len(rows)} 条，串行")
done = {}
try:
    for line in open(OUT, encoding="utf-8"):
        d = json.loads(line)
        if d.get("ok"):
            done[d["id"]] = d
except FileNotFoundError:
    pass
print(f"已有结果 {len(done)} 条，续跑")

with open(OUT, "a", encoding="utf-8") as fo:
    for i, row in enumerate(rows, 1):
        if row["编号"] in done:
            continue
        r, dt = call(row)
        loc = r.get("local") or {}
        rec = {
            "id": row["编号"], "type": row["类型"], "text": row["正文"],
            "ok": r.get("ok", False),
            "stage": r.get("stage"), "stageName": r.get("stageName"),
            "isFraud": r.get("isFraud"), "riskType": r.get("type"),
            "reason": r.get("reason"), "signals": r.get("signals"),
            "ruleStage": loc.get("stage"), "ruleScore": loc.get("score"),
            "ruleWhy": loc.get("why"),
            "ms": round(dt, 2), "err": r.get("err"),
        }
        fo.write(json.dumps(rec, ensure_ascii=False) + "\n")
        fo.flush()
        print(f"[{i}/{len(rows)}] {row['编号']} stage={rec['stage']} fraud={rec['isFraud']} "
              f"rule={rec['ruleStage']} {dt:.1f}s {rec['err'] or ''}", flush=True)
        time.sleep(0.6)
print("完成")
