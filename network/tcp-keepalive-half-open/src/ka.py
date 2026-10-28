"""对端还活着吗：半开连接、TCP keepalive、TCP_USER_TIMEOUT、应用层心跳、未认证连接的超时。每个场景打印一行结果。"""
import errno
import os
import signal
import socket
import subprocess
import sys
import threading
import time

SRV = "172.31.12.10"


def err(e):
    # 内核判定连接断开时是带 errno 的 ETIMEDOUT；settimeout 等满时 errno 为 None。Python 里两者都是 TimeoutError
    if e.errno is None:
        return "等满无结果"
    return errno.errorcode.get(e.errno, str(e.errno))


def serve(port, mode):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port))
    s.listen(16)

    def handle(c):
        if mode == "auth-timeout":
            c.settimeout(2)                  # 未认证的连接：2 秒内必须发来第一条消息
        pongs = 0
        try:
            while True:
                d = c.recv(1024)
                if not d:
                    break
                c.sendall(b"PONG\n" * d.count(b"PING"))
                pongs += 1
                if mode == "freeze" and pongs == 1:
                    os.kill(os.getpid(), signal.SIGSTOP)   # 进程冻结，内核照常处理 TCP
        except OSError:
            pass
        c.close()

    while True:
        c, _ = s.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


def connect(port, keepalive=None, user_timeout=None):
    c = socket.create_connection((SRV, port), timeout=5)
    if keepalive == "default":
        c.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    elif keepalive == "tuned":
        c.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 2)
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 1)
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 3)
    if user_timeout:
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_USER_TIMEOUT, user_timeout)
    c.sendall(b"PING\n")
    assert c.recv(16) == b"PONG\n"
    return c


def wait(c, limit, send_first=False):
    """等对端的消息或错误，最多 limit 秒；返回 (结果, 秒数)。"""
    c.settimeout(limit)
    t0 = time.monotonic()
    try:
        if send_first:
            c.sendall(b"PING\n")
        d = c.recv(16)
        r = "EOF" if d == b"" else d.decode().strip()
    except OSError as e:
        r = err(e)
    return r, time.monotonic() - t0


def partition():
    cases = {
        "idle": connect(9001),
        "keepalive-default": connect(9001, "default"),
        "keepalive-tuned": connect(9001, "tuned"),
        "write": connect(9001),
        "write-user-timeout": connect(9001, user_timeout=3000),
    }
    for rule in (["OUTPUT", "-d", SRV], ["INPUT", "-s", SRV]):
        subprocess.run(["iptables", "-A", rule[0], rule[1], rule[2], "-p", "tcp", "--dport" if rule[0] == "OUTPUT" else "--sport", "9001", "-j", "DROP"], check=True)
    results = {}

    def run(name, c):
        results[name] = wait(c, 10, send_first=name.startswith("write"))

    ts = [threading.Thread(target=run, args=kv) for kv in cases.items()]
    for t in ts:
        t.start()
    time.sleep(6)
    ss = subprocess.run(["ss", "-Htn", "state", "established", "( dport = :9001 )"], capture_output=True, text=True).stdout
    for t in ts:
        t.join()
    subprocess.run("iptables -F", shell=True)
    for name, (r, sec) in results.items():
        print(f"{name} {r} {sec:.1f}")
    print(f"established-at-6s {len(ss.strip().splitlines())}")


def frozen():
    c = connect(9002, "tuned")                  # 第一次 PONG 之后服务端进程冻结
    r1, s1 = wait(c, 8)                         # 什么也不发，只等 keepalive
    r2, s2 = wait(c, 1, send_first=True)        # 应用层心跳：发 PING，1 秒内要 PONG
    print(f"keepalive {r1} {s1:.1f}")
    print(f"heartbeat {r2} {s2:.1f}")


def unauth():
    res = {}

    def run(name, port):
        c = socket.create_connection((SRV, port))
        res[name] = wait(c, 5)

    ts = [threading.Thread(target=run, args=a) for a in (("no-timeout", 9003), ("auth-timeout", 9004))]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    for name in ("no-timeout", "auth-timeout"):
        r, sec = res[name]
        print(f"{name} {r} {sec:.1f}")


if __name__ == "__main__":
    cmd, *a = sys.argv[1:]
    if cmd == "serve":
        serve(int(a[0]), a[1])
    else:
        {"partition": partition, "frozen": frozen, "unauth": unauth}[cmd]()
