#!/usr/bin/env python3
"""汇总数据结构与编码实验的结果，断言文章中的关键结论。用法：summarize.py <输出目录>"""
import csv, statistics, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def rows(name):
    return list(csv.DictReader((out / name).open(), delimiter="\t"))

cfg = dict(l.split("\t") for l in (out / "config.tsv").read_text().splitlines())
print("信息：默认阈值 " + "，".join(f"{k}={cfg[k]}" for k in ("hash-max-listpack-entries", "hash-max-listpack-value",
      "zset-max-listpack-entries", "set-max-intset-entries", "set-max-listpack-entries", "list-max-listpack-size")))
check(cfg["hash-max-listpack-entries"] == "512" and cfg["zset-max-listpack-entries"] == "128", "Redis 8.10.1 默认 hash-max-listpack-entries=512、zset-max-listpack-entries=128")

enc = {r["key"]: r for r in rows("encodings.tsv")}
pairs = [("h:512", "listpack", "h:513", "hashtable"), ("h:v64", "listpack", "h:v65", "hashtable"),
         ("z:128", "listpack", "z:129", "skiplist"), ("s:int512", "intset", "s:int513", "hashtable"),
         ("s:str128", "listpack", "s:str129", "hashtable"), ("l:small", "listpack", "l:big", "quicklist")]
for a, ea, b, eb in pairs:
    check(enc[a]["encoding"] == ea and enc[b]["encoding"] == eb,
          f"{enc[a]['description']} → {enc[a]['encoding']}（{enc[a]['memory_usage_bytes']} 字节）；{enc[b]['description']} → {enc[b]['encoding']}（{enc[b]['memory_usage_bytes']} 字节）")
check(enc["str:int"]["encoding"] == "int", f"String 12345 → int（{enc['str:int']['memory_usage_bytes']} 字节），100 字节字符串 → {enc['str:100']['encoding']}（{enc['str:100']['memory_usage_bytes']} 字节）")

eb = rows("embstr-boundary.tsv")
limits = {r["key"]: max(int(x["value_bytes"]) for x in eb if x["key"] == r["key"] and x["encoding"] == "embstr") for r in eb}
check(all((int(r["value_bytes"]) <= limits[r["key"]]) == (r["encoding"] == "embstr") for r in eb),
      "每个 key 都只有一个分界：不超过上限是 embstr，超过后全部是 raw")
ks = sorted(limits, key=len)
desc = "；".join(f"key {len(k)} 字节时 ≤{limits[k]} 字节" for k in ks)
check(limits[ks[0]] > limits[ks[1]] > limits[ks[2]], f"embstr 的值长度上限随 key 变长而下降：{desc}（不是固定的 44 字节）")
seen = [r for r in eb if r["key"] == ks[1]]
last_emb = [r for r in seen if r["encoding"] == "embstr"][-1]; first_raw = [r for r in seen if r["encoding"] == "raw"][0]
print(f"信息：key「{ks[1]}」：{last_emb['value_bytes']} 字节值 embstr {last_emb['memory_usage_bytes']} 字节，{first_raw['value_bytes']} 字节值 raw {first_raw['memory_usage_bytes']} 字节")
hc = rows("hash-crossing.tsv")
m500, m600, mdel = (int(r["memory_usage_bytes"]) for r in hc)
check(hc[0]["encoding"] == "listpack" and hc[1]["encoding"] == "hashtable" and m600 > 3 * m500,
      f"Hash 500 个字段 listpack {m500:,} 字节（{m500 / 500:.1f} 字节/字段）；600 个字段 hashtable {m600:,} 字节（{m600 / 600:.1f} 字节/字段）")
check(hc[2]["encoding"] == "hashtable", f"{hc[2]['description']}，编码仍是 {hc[2]['encoding']}（{mdel:,} 字节），不会变回 listpack")

b = {r["layout"]: r for r in rows("buckets.tsv")}
s, b5, b10 = (int(b[k]["delta_bytes"]) for k in ("strings", "buckets:500", "buckets:1000"))
check(b["buckets:500"]["sample_encoding"] == "listpack" and b5 < 0.6 * s,
      f"10 万条小对象：独立 String key {b['strings']['keys']} 个，新增 {s / 1e6:.2f} MB；每 500 条一个 Hash（{b['buckets:500']['keys']} 个 key，listpack），新增 {b5 / 1e6:.2f} MB，节省 {1 - b5 / s:.0%}")
check(b["buckets:1000"]["sample_encoding"] == "hashtable" and b10 > b5,
      f"每 1000 条一个 Hash 超过阈值变成 hashtable，新增 {b10 / 1e6:.2f} MB，收益缩小到 {1 - b10 / s:.0%}")
print(f"信息：used_memory 总量 strings {int(b['strings']['used_memory_after']) / 1e6:.2f} MB，buckets:500 {int(b['buckets:500']['used_memory_after']) / 1e6:.2f} MB")

c = {r["key"]: r for r in rows("counting.tsv")}
sm, bm, hm = (int(c[k]["memory_usage_bytes"]) for k in ("uv:set", "uv:bitmap", "uv:hll"))
hc_ = int(c["uv:hll"]["count"])
check(int(c["uv:set"]["count"]) == 1_000_000 and int(c["uv:bitmap"]["count"]) == 1_000_000,
      f"一百万个 ID：Set（{c['uv:set']['encoding']}）{sm / 1e6:.1f} MB，Bitmap {bm / 1e3:.0f} KB，两者计数都是 1,000,000")
check(hm < 20_000 and abs(hc_ - 1_000_000) / 1e6 < 0.02,
      f"HyperLogLog {hm / 1e3:.1f} KB，PFCOUNT {hc_:,}，误差 {abs(hc_ - 1_000_000) / 1e4:.2f}%")
check(sm > 100 * bm, f"Set 是 Bitmap 的 {sm / bm:.0f} 倍、HyperLogLog 的 {sm / hm:.0f} 倍")
sp = int(c["uv:sparse"]["memory_usage_bytes"])
check(sp > 12_000_000, f"只设置偏移量 1 亿处的 1 位，Bitmap 占用 {sp / 1e6:.1f} MB")

d = rows("delete.tsv")
def med(cmd, lazy):
    v = [int(r["server_micros"]) for r in d if r["command"] == cmd and r["lazyfree_lazy_user_del"] == lazy]
    return statistics.median(v), min(v), max(v)
dm, um, lm = med("DEL", "no"), med("UNLINK", "no"), med("DEL", "yes")
check(dm[0] > 20 * um[0], f"删除 100 万元素的 Set（SLOWLOG 服务端耗时，5 次中位数）：DEL {dm[0] / 1000:.1f}ms（{dm[1] / 1000:.1f}—{dm[2] / 1000:.1f}），UNLINK {um[0]:.0f}µs（{um[1]}—{um[2]}）")
check(lm[0] < dm[0] / 20, f"打开 lazyfree-lazy-user-del 后 DEL 与 UNLINK 相当：{lm[0]:.0f}µs（{lm[1]}—{lm[2]}）；默认值 {cfg['lazyfree-lazy-user-del']}")

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
