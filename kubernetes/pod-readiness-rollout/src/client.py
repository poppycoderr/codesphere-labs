"""压测客户端：每 20ms 通过 Service 发一次新连接请求，结束时输出成功数、按原因归类的失败数和各版本的响应数。"""
import http.client
import json
import sys
import time
from collections import Counter

host, duration = sys.argv[1], float(sys.argv[2])
ok, fail, versions = 0, Counter(), Counter()
end = time.monotonic() + duration
while time.monotonic() < end:
    start = time.monotonic()
    try:
        conn = http.client.HTTPConnection(host, 80, timeout=1)
        conn.request("GET", "/")
        resp = conn.getresponse()
        body = resp.read().decode().strip()
        conn.close()
        if resp.status == 200:
            ok += 1
            versions[body] += 1
        else:
            fail[f"http {resp.status}"] += 1
    except ConnectionRefusedError:
        fail["connection refused"] += 1
    except TimeoutError:
        fail["timeout"] += 1
    except OSError as e:
        fail[type(e).__name__] += 1
    time.sleep(max(0.0, 0.02 - (time.monotonic() - start)))
print("RESULT " + json.dumps({"ok": ok, "fail": sum(fail.values()), "reasons": dict(fail), "versions": dict(versions)}, sort_keys=True), flush=True)
