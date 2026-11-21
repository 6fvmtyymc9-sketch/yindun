import glob, json, os, sys, time, urllib.request

def judge_text(text, port=8000):
    body = json.dumps({"text": text}).encode()
    t = time.time()
    req = urllib.request.Request("http://127.0.0.1:%d/judge" % port, body,
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        o = json.loads(r.read().decode())
    o["_ms"] = int((time.time() - t) * 1000)
    return o

files = sorted(glob.glob(os.path.expanduser('~/yindun/eval30_txt/*.txt')))
out = open(os.path.expanduser('~/yindun/text30_out.jsonl'), 'w', encoding='utf-8')
t0 = time.time()
for i, p in enumerate(files, 1):
    name = os.path.basename(p)
    text = open(p, encoding='utf-8').read().strip()
    try:
        o = judge_text(text)
    except Exception as e:
        o = {"error": "%s: %s" % (type(e).__name__, e)}
    o["file"] = name.replace('.txt', '.wav')
    o["chars"] = len(text)
    out.write(json.dumps(o, ensure_ascii=False) + "\n")
    out.flush()
    print("[%2d/%d] %s -> %s p=%s %sms" % (i, len(files), name, o.get("is_fraud"), o.get("p"), o.get("_ms")), flush=True)
out.close()
print("TOTAL %.1f s" % (time.time() - t0), flush=True)
