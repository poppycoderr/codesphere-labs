"""小包的 40 毫秒：服务端读满 20 字节（10 字节头 + 10 字节体）后回 10 字节；客户端按不同写法发送，测往返时间。"""
import socket
import statistics
import sys
import time

SRV = "172.31.11.10"
HEAD, BODY, RESP = b"H" * 10, b"B" * 10, b"R" * 10


def serve(port, server_nodelay):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port))
    s.listen(4)
    while True:
        c, _ = s.accept()
        if server_nodelay:
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        buf = b""
        while True:
            d = c.recv(4096)
            if not d:
                break
            buf += d
            while len(buf) >= 20:
                buf = buf[20:]
                c.sendall(RESP)
        c.close()


def client(port, mode, warm, n):
    c = socket.create_connection((SRV, port))
    if mode == "nodelay":
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    times = []
    for i in range(warm + n):
        t0 = time.perf_counter()
        if mode in ("nagle", "nodelay"):
            c.send(HEAD)
            c.send(BODY)
        elif mode == "joined":
            c.sendall(HEAD + BODY)
        elif mode == "sendmsg":
            c.sendmsg([HEAD, BODY])
        got = b""
        while len(got) < 10:
            got += c.recv(10 - len(got))
        if i >= warm:
            times.append((time.perf_counter() - t0) * 1000)
    c.close()
    return times


if __name__ == "__main__":
    cmd, *a = sys.argv[1:]
    if cmd == "serve":
        serve(int(a[0]), a[1] == "1")
    else:
        ts = client(int(a[0]), a[1], int(a[2]), int(a[3]))
        print(f"{statistics.median(ts):.3f} {min(ts):.3f} {max(ts):.3f} " + " ".join(f"{t:.2f}" for t in ts))
