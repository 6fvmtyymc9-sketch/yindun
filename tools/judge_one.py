import base64, json, sys, time, urllib.request

def judge(path, port=8000):
    raw = open(path, 'rb').read()
    body = json.dumps({"audio_b64": base64.b64encode(raw).decode(),
                       "mime": "audio/wav",
                       "name": path.split('/')[-1]}).encode()
    t = time.time()
    req = urllib.request.Request("http://127.0.0.1:%d/judge" % port, body,
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        o = json.loads(r.read().decode())
    o["_ms"] = int((time.time() - t) * 1000)
    return o

if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(json.dumps(judge(p), ensure_ascii=False), flush=True)
