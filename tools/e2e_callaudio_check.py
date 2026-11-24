#!/usr/bin/env python3
"""端到端验收：把一段录音打给 ECS 的 /api/callaudio，确认第三票（SFT）真的参与了判定。

dryRun=True：口径与正式调用完全一致，但不写库、不推告警，适合接入验收。
"""
import base64
import json
import sys
import time
import urllib.request

MP3 = sys.argv[1] if len(sys.argv) > 1 else "/tmp/13900001111_20261109103015.mp3"
URL = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8080/api/callaudio"

b64 = base64.b64encode(open(MP3, "rb").read()).decode("ascii")
body = json.dumps({
    "audio": b64,
    "name": MP3.rsplit("/", 1)[-1],
    "number": "13900001111",
    "durSec": 55,
    "dryRun": True,
}, ensure_ascii=False).encode("utf-8")

t0 = time.time()
req = urllib.request.Request(URL, data=body, headers={"Content-Type": "application/json"})
try:
    with urllib.request.urlopen(req, timeout=200) as resp:
        data = json.loads(resp.read().decode("utf-8"))
except Exception as e:
    print(f"请求失败：{type(e).__name__}: {e}")
    sys.exit(1)

el = time.time() - t0
print(f"端到端耗时 {el:.1f}s\n")

# 只打印关键字段，全量太吵
def g(*keys):
    cur = data
    for k in keys:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(k)
    return cur

print("ok       :", data.get("ok"))
print("stage    :", data.get("stage"))
print("basis    :", data.get("basis"))
print("is_fraud :", data.get("isFraud") or data.get("is_fraud"))
print("sftState :", data.get("sftState"))
print("wouldAlert:", data.get("wouldAlert"))
print()
print("--- 第三票 sft ---")
print(json.dumps(data.get("sft"), ensure_ascii=False, indent=1))
print()
print("--- 各段耗时 ---")
for k in ("asrMs", "ms", "sftMs", "llmMs", "totalMs", "elapsedMs"):
    if k in data:
        print(f"  {k}: {data[k]}")
print()
print("--- 顶层字段 ---")
print(", ".join(sorted(data.keys())))
