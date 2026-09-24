#!/usr/bin/env python3
"""汇总内存淘汰实验的结果，断言文章中的关键结论。用法：summarize.py <输出目录>"""
import csv, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def rows(name):
    return list(csv.DictReader((out / name).open(), delimiter="\t"))
def kv(name):
    return dict(l.split(":", 1) for l in (out / name).read_text().splitlines() if ":" in l and not l.startswith("#"))

cfg = dict(l.split("\t") for l in (out / "config-defaults.tsv").read_text().splitlines())
check(cfg["maxmemory"] == "0" and cfg["maxmemory-policy"] == "noeviction", f"默认 maxmemory={cfg['maxmemory']}、maxmemory-policy={cfg['maxmemory-policy']}")

p = {r["case"]: r for r in rows("policies.tsv")}
n = p["noeviction"]
check(int(n["write_errors"]) > 30000 and n["evicted_keys"] == "0" and n["next_set"].startswith("OOM") and n["get_first_key"].startswith("v"),
      f"noeviction：6 万次写入失败 {int(n['write_errors']):,} 次，淘汰 0，库中 {int(n['dbsize']):,} 个；之后写入返回 OOM，读取仍正常")
for c in ("allkeys-lru", "allkeys-lfu"):
    r = p[c]
    check(r["write_errors"] == "0" and int(r["evicted_keys"]) > 30000 and r["next_set"] == "OK",
          f"{c}：写入全部成功，淘汰 {int(r['evicted_keys']):,} 个，库中剩 {int(r['dbsize']):,} 个")
v = p["volatile-lru"]
check(abs(int(v["write_errors"]) - int(n["write_errors"])) < 500 and v["evicted_keys"] == "0" and v["next_set"].startswith("OOM"),
      f"volatile-lru 且没有 key 带 TTL：失败 {int(v['write_errors']):,} 次、淘汰 0，与 noeviction 相同")
m = p["volatile-lru-mixed"]
check(m["first_15000_survived"] == "15000" and int(m["keys_with_ttl"]) < 15000,
      f"volatile-lru，先写 1.5 万条不带 TTL 的常驻数据、再写 4.5 万条带 TTL 的缓存：常驻数据 {int(m['first_15000_survived']):,} 条全部保留，淘汰 {int(m['evicted_keys']):,} 个全部来自缓存，缓存只剩 {int(m['keys_with_ttl']):,} / 45,000 条")

s = {r["policy"]: r for r in rows("scan-pollution.tsv")}
check(int(s["allkeys-lru"]["hot_survived"]) < 100 and int(s["allkeys-lfu"]["hot_survived"]) > 950,
      f"1000 个热 key 各读 30 次后遇到 6 万个一次性写入：allkeys-lru 存活 {s['allkeys-lru']['hot_survived']} 个，allkeys-lfu 存活 {s['allkeys-lfu']['hot_survived']} 个")

def expiry(name, first_expire, last_expire):
    ts = rows(f"expiry-{name}.tsv")
    load = dict(l.split(" ", 1) for l in (out / f"expiry-{name}-load.txt").read_text().splitlines() if l.startswith("load_"))
    dur = float(load["load_finished"]) - float(load["load_started"])
    last = last_expire + dur
    stale = [r for r in ts if float(r["t_seconds"]) > last]
    lingering = max(int(r["keys"]) for r in stale)
    cleared = next(float(r["t_seconds"]) for r in ts if float(r["t_seconds"]) > first_expire and r["keys"] == "0")
    return ts, dur, last, lingering, cleared, max(float(r["expired_stale_perc"]) for r in ts)
ts, dur, last, lingering, cleared, stale = expiry("same-ttl", 3.0, 3.0)
check(lingering > 1000 and cleared - last < 3,
      f"20 万个 key 用 {dur:.2f}s 写完、TTL 3 秒：最后一个 key 到期（{last:.2f}s）后仍有最多 {lingering:,} 个已过期的 key 留在库里，{cleared:.1f}s 时全部清除（到期后约 {cleared - last:.1f}s）；expired_stale_perc 最高 {stale:.1f}%")
used = [int(r["used_memory"]) for r in ts]
print(f"信息：同时过期 used_memory {max(used) / 1e6:.1f} MB → {min(used) / 1e6:.2f} MB")
ts2, dur2, last2, _, cleared2, stale2 = expiry("jitter-ttl", 3.0, 9.0)
first_drop = next(float(r["t_seconds"]) for r in ts2 if float(r["t_seconds"]) > dur2 and int(r["keys"]) < 200000)
check(cleared2 > cleared + 3, f"TTL 分散到 3—9 秒：从 {first_drop:.1f}s 开始减少，{cleared2:.1f}s 清完，过期删除分摊到约 {cleared2 - first_drop:.0f} 秒；expired_stale_perc 最高 {stale2:.1f}%")

me = kv("memory-after-expiry.txt")
check(float(me["mem_fragmentation_ratio"]) > 10 and int(me["used_memory"]) < 5_000_000,
      f"过期清完后 used_memory {int(me['used_memory']) / 1e6:.2f} MB、RSS {int(me['used_memory_rss']) / 1e6:.1f} MB，mem_fragmentation_ratio {me['mem_fragmentation_ratio']}；allocator_frag_ratio {me['allocator_frag_ratio']}，allocator_frag_bytes {int(me['allocator_frag_bytes']) / 1e6:.1f} MB")

lo, de, df = kv("fragmentation-loaded.txt"), kv("fragmentation-deleted.txt"), kv("fragmentation-defragged.txt")
mb = lambda d, k: int(d[k]) / 1e6
check(float(de["allocator_frag_ratio"]) > 2.5 and mb(de, "used_memory_rss") > 0.9 * mb(lo, "used_memory_rss"),
      f"50 万个值删掉四分之三：used_memory {mb(lo, 'used_memory'):.0f} → {mb(de, 'used_memory'):.0f} MB，RSS {mb(lo, 'used_memory_rss'):.0f} → {mb(de, 'used_memory_rss'):.0f} MB，allocator_frag_ratio {lo['allocator_frag_ratio']} → {de['allocator_frag_ratio']}（碎片 {mb(de, 'allocator_frag_bytes'):.0f} MB）")
tl = rows("defrag-timeline.tsv")
check((out / "activedefrag-set.txt").read_text().strip() == "OK", "CONFIG SET activedefrag yes 成功（jemalloc 构建支持主动碎片整理）")
check(mb(df, "allocator_frag_bytes") < 0.5 * mb(de, "allocator_frag_bytes") and mb(df, "used_memory_rss") < 0.7 * mb(de, "used_memory_rss"),
      f"打开 activedefrag 40 秒后：碎片 {mb(de, 'allocator_frag_bytes'):.0f} → {mb(df, 'allocator_frag_bytes'):.0f} MB，RSS {mb(de, 'used_memory_rss'):.0f} → {mb(df, 'used_memory_rss'):.0f} MB，used_memory 不变（{mb(df, 'used_memory'):.0f} MB）")
first_rss_drop = next(int(r["t_seconds"]) for r in tl if int(r["used_memory_rss"]) < 0.9 * int(tl[0]["used_memory_rss"]))
cpu = float(tl[-1]["used_cpu_user"]) - float(tl[0]["used_cpu_user"])
check(cpu > 0, f"碎片字节在前 {next(int(r['t_seconds']) for r in tl if float(r['allocator_frag_ratio']) < 2)} 秒内基本降完，RSS 第 {first_rss_drop} 秒才开始下降；40 秒内 Redis 用户态 CPU 增加 {cpu:.1f} 秒，active_defrag_running 最后仍为 {tl[-1]['active_defrag_running']}%")
check((out / "fragmentation-dbsize.txt").read_text().strip() == "125000", "整理后 key 数仍为 125,000")

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
