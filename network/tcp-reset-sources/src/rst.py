"""RST 的几种来源。serve 在服务端命名空间运行，其余子命令在客户端运行，每个打印一行结果。"""
import errno
import socket
import struct
import sys
import time

SRV = "172.31.10.10"


def err(e):
    if isinstance(e, socket.timeout):
        return "timeout"
    return errno.errorcode.get(e.errno, str(e.errno))


def serve(port, mode):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port))
    s.listen(16)
    while True:
        c, _ = s.accept()
        if mode == "unread":                 # 收到数据但不读就关闭
            time.sleep(0.3)
            c.close()
        elif mode == "read-then-close":      # 读完再关闭（对照）
            c.settimeout(1)
            try:
                c.recv(65536)
            except OSError:
                pass
            c.close()
        elif mode == "linger0":              # SO_LINGER 超时 0：关闭时直接复位
            c.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            c.close()
        elif mode == "close":                # 正常关闭
            c.close()
        elif mode == "keepalive":            # 一问一答，空闲 1 秒后服务端关闭
            c.settimeout(1)
            try:
                while True:
                    d = c.recv(1024)
                    if not d:
                        break
                    c.sendall(b"PONG\n")
            except socket.timeout:
                pass
            c.close()


def recv_after(port, payload, wait):
    c = socket.create_connection((SRV, port), timeout=3)
    if payload:
        c.sendall(payload)
    time.sleep(wait)
    try:
        d = c.recv(1024)
        r = "EOF" if d == b"" else repr(d)
    except OSError as e:
        r = err(e)
    c.close()
    return r


def writes_after_close(port):
    c = socket.create_connection((SRV, port), timeout=3)
    time.sleep(0.3)                          # 服务端已经 close，FIN 已到
    out = []
    for i in range(3):
        try:
            c.send(b"x" * 100)
            out.append("ok")
        except OSError as e:
            out.append(err(e))
        time.sleep(0.2)
    c.close()
    return out


def pool(port, idle, stale_check):
    c = socket.create_connection((SRV, port), timeout=3)
    c.sendall(b"PING\n")
    first = c.recv(1024).decode().strip()
    time.sleep(idle)                         # 连接在池里空闲
    if stale_check:
        c.setblocking(False)
        try:
            peek = c.recv(1, socket.MSG_PEEK)
            stale = peek == b""
        except BlockingIOError:
            stale = False
        c.setblocking(True)
        c.settimeout(3)
        if stale:
            c.close()
            return first, "复用前检查到对端已关闭，丢弃并新建连接", fresh(port)
    try:
        c.sendall(b"PING\n")
        sent = "发送成功"
    except OSError as e:
        sent = "发送失败 " + err(e)
    try:
        d = c.recv(1024)
        got = "读到 EOF" if d == b"" else "读到 " + d.decode().strip()
    except OSError as e:
        got = "读取 " + err(e)
    c.close()
    return first, sent, got


def fresh(port):
    c = socket.create_connection((SRV, port), timeout=3)
    c.sendall(b"PING\n")
    r = c.recv(1024).decode().strip()
    c.close()
    return "新连接得到 " + r


def refused(port):
    try:
        socket.create_connection((SRV, port), timeout=3)
        return "connected"
    except OSError as e:
        return err(e)


if __name__ == "__main__":
    cmd, *a = sys.argv[1:]
    if cmd == "serve":
        serve(int(a[0]), a[1])
    elif cmd == "unread":
        print(recv_after(int(a[0]), b"y" * 100, 0.6))
    elif cmd == "linger0":
        print(recv_after(int(a[0]), b"", 0.3))
    elif cmd == "writes":
        print(" ".join(writes_after_close(int(a[0]))))
    elif cmd == "pool":
        print("|".join(pool(int(a[0]), float(a[1]), a[2] == "1")))
    elif cmd == "refused":
        print(refused(int(a[0])))
