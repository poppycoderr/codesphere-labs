#!/usr/bin/env python3
"""生成 RESP 协议的批量命令，交给 redis-cli --pipe 写入。数据完全由序号决定，没有随机数。
用法：gen.py <模式> [参数...]
  strings <n>                     SET user:<i> <value>
  buckets <n> <size>              HSET user:h:<i // size> <i> <value>
  hash <key> <n> [value_len]      HSET <key> f<i> <value>
  zset <key> <n>                  ZADD <key> <i> m<i>
  set <key> <n> [str]             SADD <key> <i>（str 时成员为 s<i>）
  list <key> <n> [value_len]      RPUSH <key> <value>
  bitmap <key> <n>                SETBIT <key> <i> 1，i 从 1 到 n
  hll <key> <n>                   PFADD <key> <i>，i 从 1 到 n"""
import sys

out = sys.stdout.buffer
def cmd(*args):
    parts = [b"*%d\r\n" % len(args)]
    for a in args:
        b = a if isinstance(a, bytes) else str(a).encode()
        parts.append(b"$%d\r\n%s\r\n" % (len(b), b))
    out.write(b"".join(parts))
def value(i, n=16):
    return (f"u{i:07d}-" + "p" * n)[:n]
def batched(n, size=1000):
    for start in range(0, n, size):
        yield range(start, min(n, start + size))

mode, args = sys.argv[1], sys.argv[2:]
if mode == "strings":
    for i in range(int(args[0])): cmd("SET", f"user:{i}", value(i))
elif mode == "buckets":
    n, size = int(args[0]), int(args[1])
    for i in range(n): cmd("HSET", f"user:h:{i // size}", i, value(i))
elif mode == "hash":
    key, n = args[0], int(args[1]); vlen = int(args[2]) if len(args) > 2 else 8
    for chunk in batched(n):
        flat = [x for i in chunk for x in (f"f{i}", value(i, vlen))]
        cmd("HSET", key, *flat)
elif mode == "zset":
    key, n = args[0], int(args[1])
    for chunk in batched(n): cmd("ZADD", key, *[x for i in chunk for x in (i, f"m{i}")])
elif mode == "set":
    key, n = args[0], int(args[1]); prefix = "s" if len(args) > 2 else ""
    for chunk in batched(n): cmd("SADD", key, *[f"{prefix}{i}" for i in chunk])
elif mode == "list":
    key, n = args[0], int(args[1]); vlen = int(args[2]) if len(args) > 2 else 8
    for chunk in batched(n): cmd("RPUSH", key, *[value(i, vlen) for i in chunk])
elif mode == "bitmap":
    key, n = args[0], int(args[1])
    for i in range(1, n + 1): cmd("SETBIT", key, i, 1)
elif mode == "hll":
    key, n = args[0], int(args[1])
    for chunk in batched(n): cmd("PFADD", key, *[i + 1 for i in chunk])
else:
    sys.exit(f"未知模式 {mode}")
