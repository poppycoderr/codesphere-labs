#!/usr/bin/env python3
"""生成 RESP 协议的批量命令，交给 redis-cli --pipe。数据由序号决定，没有随机数。
用法：gen.py <模式> [参数...]
  set <前缀> <n> <值字节> [ttl_ms] [jitter_ms]   SET <前缀><i> <值> [PX ttl + i*7919 % jitter]
  sadd <key> <n>                                SADD <key>，每条命令 1000 个成员
  publish <频道> <n> <字节>                      PUBLISH <频道> <消息>"""
import sys
out = sys.stdout.buffer
def cmd(*args):
    parts = [b"*%d\r\n" % len(args)]
    for a in args:
        b = a if isinstance(a, bytes) else str(a).encode()
        parts.append(b"$%d\r\n%s\r\n" % (len(b), b))
    out.write(b"".join(parts))
mode, a = sys.argv[1], sys.argv[2:]
if mode == "set":
    prefix, n, size = a[0], int(a[1]), int(a[2]); ttl = int(a[3]) if len(a) > 3 else 0; jitter = int(a[4]) if len(a) > 4 else 0
    pad = b"v" * size
    for i in range(n):
        v = (f"{i}:".encode() + pad)[:size]
        if ttl: cmd("SET", f"{prefix}{i}", v, "PX", ttl + (i * 7919 % jitter if jitter else 0))
        else: cmd("SET", f"{prefix}{i}", v)
elif mode == "sadd":
    key, n = a[0], int(a[1])
    for s in range(0, n, 1000): cmd("SADD", key, *range(s, min(n, s + 1000)))
elif mode == "publish":
    ch, n, size = a[0], int(a[1]), int(a[2]); msg = b"m" * size
    for _ in range(n): cmd("PUBLISH", ch, msg)
else:
    sys.exit(f"未知模式 {mode}")
