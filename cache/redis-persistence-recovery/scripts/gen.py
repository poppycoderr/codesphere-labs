#!/usr/bin/env python3
"""生成 RESP 协议的 SET 命令，交给 redis-cli --pipe。用法：gen.py <前缀> <起> <止> <值字节>，值由序号决定"""
import sys
prefix, lo, hi, size = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
out = sys.stdout.buffer
for i in range(lo, hi):
    k, v = f"{prefix}{i}".encode(), (f"{i}:" + "v" * size)[:size].encode()
    out.write(b"*3\r\n$3\r\nSET\r\n$%d\r\n%s\r\n$%d\r\n%s\r\n" % (len(k), k, len(v), v))
