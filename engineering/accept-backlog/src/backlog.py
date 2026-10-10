"""监听队列：服务端 listen 之后一直不 accept，客户端能连上多少个、连上之后发请求会怎样、队列满了之后新连接看到什么。

在容器自己的网络命名空间里用回环地址完成，参数从命令行给出 listen 的 backlog；内核参数由 docker run --sysctl 设置。
"""
import select, socket, sys, time

def read(path):
    try: return open(path).read().strip()
    except OSError: return "不可读"

def netstat(name):
    lines = open("/proc/net/netstat").read().splitlines()
    for head, vals in zip(lines[::2], lines[1::2]):
        keys = head.split()
        if name in keys: return int(vals.split()[keys.index(name)])
    return -1

def out(k, v): print(f"{k}\t{v}")

backlog = int(sys.argv[1])
tag = sys.argv[2]
somaxconn = read("/proc/sys/net/core/somaxconn")
abort = read("/proc/sys/net/ipv4/tcp_abort_on_overflow")
out(f"{tag}.setup", f"listen(backlog={backlog})，net.core.somaxconn = {somaxconn}，tcp_abort_on_overflow = {abort}")

server = socket.socket(); server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("127.0.0.1", 9000)); server.listen(backlog)          # 之后一直不调用 accept
before_overflow, before_drop = netstat("ListenOverflows"), netstat("ListenDrops")

# 同时发起 40 个连接，2 秒后看哪些握手完成了
clients = []
for _ in range(40):
    c = socket.socket(); c.setblocking(False)
    try: c.connect(("127.0.0.1", 9000))
    except BlockingIOError: pass
    clients.append(c)
deadline = time.time() + 2
connected, pending = [], list(clients)
while pending and time.time() < deadline:
    _, writable, _ = select.select([], pending, [], 0.2)
    for c in writable:
        pending.remove(c)
        if c.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR) == 0: connected.append(c)
out(f"{tag}.connect", f"服务端不 accept，同时发起 40 个连接，2 秒后：握手完成 {len(connected)} 个，仍在等待 {len(pending)} 个")
out(f"{tag}.counters", f"内核计数器 ListenOverflows 增加了 = {netstat('ListenOverflows') > before_overflow}，ListenDrops 增加了 = {netstat('ListenDrops') > before_drop}")

# 握手完成的连接上发一个请求
first = connected[0]; first.setblocking(True); first.settimeout(1)
sent = first.send(b"GET /health HTTP/1.1\r\nHost: demo\r\n\r\n")
try: first.recv(100); got = "收到响应"
except socket.timeout: got = "1 秒内没有任何响应（读超时）"
except OSError as e: got = f"{type(e).__name__}"
out(f"{tag}.request", f"在握手完成的连接上发请求：send 返回 {sent} 字节（成功）；随后读取：{got}")

# 握手没有完成的连接：阻塞式 connect 加 1 秒超时
probe = socket.socket(); probe.settimeout(1)
t = time.time()
try: probe.connect(("127.0.0.1", 9000)); res = "连接成功"
except socket.timeout: res = "connect 超时"
except OSError as e: res = type(e).__name__
out(f"{tag}.new_connection", f"队列满了之后再来一个连接（连接超时设 1 秒）：{res}，耗时约 {round(time.time() - t)} 秒")
