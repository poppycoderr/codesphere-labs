"""client.py spread <主机> <次数>：每次新建连接 / 复用一条长连接，统计各 Pod 的响应数。
client.py probe <主机> [端口]：请求一次，输出结果或异常类型与耗时。
client.py resolve <名字>：用 getaddrinfo 解析，输出地址与耗时。"""
import http.client
import socket
import sys
import time
from collections import Counter

mode, host = sys.argv[1], sys.argv[2]
if mode == "spread":
    n = int(sys.argv[3])
    fresh = Counter()
    for _ in range(n):
        c = http.client.HTTPConnection(host, 80, timeout=2)
        c.request("GET", "/")
        fresh[c.getresponse().read().decode()] += 1
        c.close()
    kept, c = Counter(), http.client.HTTPConnection(host, 80, timeout=2)
    for _ in range(n):
        c.request("GET", "/")
        kept[c.getresponse().read().decode()] += 1
    print("新建连接：" + " ".join(f"{v}" for v in sorted(fresh.values(), reverse=True)) + f"（{len(fresh)} 个 Pod）；"
          + "复用一条连接：" + " ".join(f"{v}" for v in sorted(kept.values(), reverse=True)) + f"（{len(kept)} 个 Pod）")
elif mode == "probe":
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 80
    t = time.monotonic()
    try:
        c = http.client.HTTPConnection(host, port, timeout=3)
        c.request("GET", "/")
        r = f"HTTP {c.getresponse().status}"
    except ConnectionRefusedError:
        r = "连接被拒绝"
    except TimeoutError:
        r = "超时"
    except OSError as e:
        r = type(e).__name__
    print(f"{r}，耗时 {(time.monotonic() - t) * 1000:.0f}ms")
elif mode == "resolve":
    t = time.monotonic()
    try:
        addrs = sorted({a[4][0] for a in socket.getaddrinfo(host, 80, proto=socket.IPPROTO_TCP)})
        r = " ".join(addrs)
    except socket.gaierror as e:
        r = f"解析失败（{e.strerror}）"
    print(f"{r}，耗时 {(time.monotonic() - t) * 1000:.0f}ms")
