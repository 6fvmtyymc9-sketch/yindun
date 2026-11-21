import glob, json, os, sys, time
sys.path.insert(0, os.path.expanduser('~/yindun'))
from judge_one import judge

files = sorted(glob.glob(os.path.expanduser('~/yindun/eval30/*.wav')))
out = open(os.path.expanduser('~/yindun/eval30_out.jsonl'), 'w', encoding='utf-8')
t0 = time.time()
for i, p in enumerate(files, 1):
    name = os.path.basename(p)
    try:
        o = judge(p)
    except Exception as e:
        o = {"error": "%s: %s" % (type(e).__name__, e)}
    o["file"] = name
    out.write(json.dumps(o, ensure_ascii=False) + "\n")
    out.flush()
    print("[%2d/%d] %s -> is_fraud=%s p=%s %sms" % (
        i, len(files), name, o.get("is_fraud"), o.get("p"), o.get("_ms")), flush=True)
out.close()
print("TOTAL %.1f s" % (time.time() - t0), flush=True)
