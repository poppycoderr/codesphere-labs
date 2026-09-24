#!/usr/bin/env python3
"""把容器内采样的原始 INFO 输出整理成时间序列。t_seconds 以开始写入的时刻为 0。
用法：expiry_tsv.py <原始采样> <写入起止时间>"""
import re, sys

load = dict(l.split(" ", 1) for l in open(sys.argv[2]).read().splitlines() if l.startswith("load_"))
t0 = float(load["load_started"])
print("t_seconds\tdbsize\tkeys\texpires\texpired_keys\texpired_stale_perc\tused_memory\tused_memory_rss")
for block in open(sys.argv[1]).read().split("@@ ")[1:]:
    lines = block.splitlines(); v = dict(l.split(":", 1) for l in lines[1:] if ":" in l)
    ks = re.search(r"keys=(\d+),expires=(\d+)", v.get("db0", "")) or None
    keys, expires = (ks.group(1), ks.group(2)) if ks else ("0", "0")
    print(f"{float(lines[0]) - t0:.2f}\t{v['dbsize']}\t{keys}\t{expires}\t{v['expired_keys']}\t{v['expired_stale_perc']}\t{v['used_memory']}\t{v['used_memory_rss']}")
