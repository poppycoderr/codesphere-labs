#!/usr/bin/env python3
"""生成 RESP 协议的批量命令，交给 redis-cli --pipe 执行。数据完全由序号决定，没有随机数。
用法：gen.py <模式> [参数...]
  set <前缀> <起> <止> <值字节> [ttl_ms] [jitter_ms]   SET <前缀><i> <值> [PX ttl + i*7919 % jitter]
  mixed <前缀> <n>                                      SET <前缀><i>，值长度在 64—448 字节之间按序号变化
  get <前缀> <起> <止> <轮数>                           每个 key 读取若干轮
  del-except <前缀> <n> <保留模数>                       删除序号不能被模数整除的 key"""
import sys

out = sys.stdout.buffer
def cmd(*args):
    parts = [b"*%d\r\n" % len(args)]
    for a in args:
        b = str(a).encode()
        parts.append(b"$%d\r\n%s\r\n" % (len(b), b))
    out.write(b"".join(parts))
def value(i, n):
    return (f"v{i:09d}:" + "x" * n)[:n]

mode, a = sys.argv[1], sys.argv[2:]
if mode == "set":
    prefix, lo, hi, size = a[0], int(a[1]), int(a[2]), int(a[3])
    ttl = int(a[4]) if len(a) > 4 else 0; jitter = int(a[5]) if len(a) > 5 else 0
    for i in range(lo, hi):
        if ttl: cmd("SET", f"{prefix}{i}", value(i, size), "PX", ttl + (i * 7919 % jitter if jitter else 0))
        else: cmd("SET", f"{prefix}{i}", value(i, size))
elif mode == "mixed":
    prefix, n = a[0], int(a[1])
    for i in range(n): cmd("SET", f"{prefix}{i}", value(i, 64 + (i * 37) % 385))
elif mode == "get":
    prefix, lo, hi, rounds = a[0], int(a[1]), int(a[2]), int(a[3])
    for _ in range(rounds):
        for i in range(lo, hi): cmd("GET", f"{prefix}{i}")
elif mode == "del-except":
    prefix, n, keep = a[0], int(a[1]), int(a[2])
    for i in range(n):
        if i % keep: cmd("DEL", f"{prefix}{i}")
else:
    sys.exit(f"未知模式 {mode}")
