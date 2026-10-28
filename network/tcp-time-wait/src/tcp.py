"""TIME_WAIT 与端口复用的小工具，每个子命令打印一行结果。"""
import errno
import select
import socket
import sys
import time

SRV = "172.31.9.10"


def listener(port, reuseaddr=False, reuseport=False):
    s = socket.socket()
    if reuseaddr:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if reuseport:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    s.bind(("0.0.0.0", port))
    s.listen(128)
    return s


def err(e):
    return errno.errorcode.get(e.errno, str(e.errno))


def serve(port, mode, reuseaddr, count):
    """mode=active：接受后立即关闭（服务端主动关闭）；passive：等对端关闭后再关。处理 count 个连接后退出。"""
    s = listener(port, reuseaddr == "1")
    for _ in range(count):
        c, _ = s.accept()
        if mode == "passive":
            while c.recv(1024):
                pass
        c.close()
    s.close()


def connect(host, port, who_closes):
    c = socket.create_connection((host, port), timeout=5)
    if who_closes == "server":
        while c.recv(1024):
            pass
    c.close()


def try_bind(port, reuseaddr):
    try:
        listener(port, reuseaddr == "1").close()
        print("OK")
    except OSError as e:
        print(err(e))


def reuseport_pair(port, n):
    a = listener(port, reuseport=True)
    try:
        b = listener(port, reuseport=True)
    except OSError as e:
        print("second-bind", err(e))
        return
    counts = {a: 0, b: 0}
    got = 0
    while got < n:
        r, _, _ = select.select([a, b], [], [], 10)
        if not r:
            break
        for s in r:
            c, _ = s.accept()
            c.close()
            counts[s] += 1
            got += 1
    print(counts[a], counts[b])


def burst(host, port, n):
    """连续建连 n 次、客户端先关闭；打印成功数、第一次失败的错误与用过的不同本地端口数。"""
    ok, fail, ports = 0, None, set()
    for _ in range(n):
        try:
            c = socket.create_connection((host, port), timeout=5)
            ports.add(c.getsockname()[1])
            c.close()
            ok += 1
        except OSError as e:
            fail = err(e)
            break
    print(ok, fail or "-", len(ports))


def connect_once(host, port):
    try:
        c = socket.create_connection((host, port), timeout=5)
        print("OK", c.getsockname()[1])
        c.close()
    except OSError as e:
        print(err(e))


if __name__ == "__main__":
    cmd, *a = sys.argv[1:]
    if cmd == "serve":
        serve(int(a[0]), a[1], a[2], int(a[3]))
    elif cmd == "connect":
        connect(a[0], int(a[1]), a[2])
    elif cmd == "bind":
        try_bind(int(a[0]), a[1])
    elif cmd == "reuseport":
        reuseport_pair(int(a[0]), int(a[1]))
    elif cmd == "burst":
        burst(a[0], int(a[1]), int(a[2]))
    elif cmd == "once":
        connect_once(a[0], int(a[1]))
    elif cmd == "connect-many":
        for _ in range(int(a[2])):
            c = socket.create_connection((a[0], int(a[1])), timeout=5)
            c.close()
            time.sleep(0.01)
