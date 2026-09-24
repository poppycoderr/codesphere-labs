#!/usr/bin/env python3
"""按阶段汇总探测客户端的延迟，并与 SLOWLOG、LATENCY 对照，断言文章中的关键结论。用法：summarize.py <输出目录>"""
import csv, json, re, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)

phases = [(int(r["at_ms"]), r["phase"]) for r in csv.DictReader((out / "phases.tsv").open(), delimiter="\t")]
probe = [(int(r["sent_at_ms"]), int(r["latency_us"]), int(r["delay_since_due_us"])) for r in csv.DictReader((out / "probe.tsv").open(), delimiter="\t")]
def window(name, idx=0):
    starts = [(t, i) for i, (t, n) in enumerate(phases) if n == name]
    t, i = starts[idx]
    return t, phases[i + 1][0]
def stats(name, idx=0):
    a, b = window(name, idx)
    lat = sorted(p[1] for p in probe if a <= p[0] < b)
    delay = max(p[2] for p in probe if a <= p[0] < b)
    pct = lambda q: lat[min(len(lat) - 1, int(len(lat) * q))]
    return dict(n=len(lat), p50=pct(0.5), p99=pct(0.99), max=lat[-1], delay=delay, start=a, end=b)

# SLOWLOG：redis-cli --json 输出，每条为 [id, 时间戳（秒）, 耗时（微秒）, 参数, 客户端地址, 客户端名, …]
slow = [(e[1], e[2], e[3][0].upper()) for e in json.load((out / "slowlog.json").open())]
def slow_in(name):
    a, b = window(name)
    return [(cmd, us) for ts, us, cmd in slow if a // 1000 - 1 <= ts <= b // 1000]
latest = {e[0]: e for e in json.load((out / "latency-latest.json").open())}  # [事件, 最近时刻, 最近毫秒, 最大毫秒]
events = set(latest)

s = {n: stats(n) for n in ("baseline", "del", "unlink", "expiry_same", "expiry_jitter", "lua", "keys", "fork", "slow_subscriber")}
for n, v in s.items():
    print(f"信息：{n}：{v['n']} 次探测，p50 {v['p50']}µs，p99 {v['p99']}µs，最大往返 {v['max'] / 1000:.1f}ms，最大排队延迟 {v['delay'] / 1000:.1f}ms；SLOWLOG {slow_in(n) or '无'}")
b = s["baseline"]
intr = (out / "intrinsic-latency.txt").read_text()
intr_max = (re.findall(r"Max latency so far: (\d+) microseconds", intr) or ["-"])[-1]
check(b["p99"] < 2000, f"基线：p50 {b['p50']}µs、p99 {b['p99']}µs、最大 {b['max'] / 1000:.1f}ms；容器内 intrinsic latency 最大 {intr_max}µs")
d, u = s["del"], s["unlink"]
dcmd = [us for cmd, us in slow_in("del") if cmd == "DEL"]
check(dcmd and d["delay"] > 20000 and u["delay"] < d["delay"] / 3,
      f"DEL 百万成员的 Set：SLOWLOG 记录 {dcmd[0] / 1000:.1f}ms，探测请求最多被推迟 {d['delay'] / 1000:.1f}ms；UNLINK 时最多 {u['delay'] / 1000:.1f}ms、SLOWLOG 无记录")
es, ej = s["expiry_same"], s["expiry_jitter"]
check(not slow_in("expiry_same") and "expire-cycle" in events and es["delay"] > 2 * ej["delay"],
      f"50 万个 key 集中过期：探测最多推迟 {es['delay'] / 1000:.1f}ms（p99 {es['p99']}µs），SLOWLOG 没有记录，LATENCY 的 expire-cycle 事件最大 {latest['expire-cycle'][3]}ms；分散过期时最多 {ej['delay'] / 1000:.1f}ms")
lcmd = [us for cmd, us in slow_in("lua") if cmd == "EVAL"]
check(lcmd and s["lua"]["delay"] > 0.8 * lcmd[0], f"长 Lua：SLOWLOG 记录 EVAL {lcmd[0] / 1000:.0f}ms，探测最多推迟 {s['lua']['delay'] / 1000:.0f}ms")
kcmd = [us for cmd, us in slow_in("keys") if cmd == "KEYS"]
check(kcmd and s["keys"]["delay"] > 0.8 * kcmd[0], f"KEYS 遍历 100 万个 key：SLOWLOG 记录 {kcmd[0] / 1000:.0f}ms，探测最多推迟 {s['keys']['delay'] / 1000:.0f}ms")
fk = [cmd for cmd, us in slow_in("fork")]
check("fork" in events and not any(c in ("BGSAVE", "SET") for c in fk),
      f"save 规则触发的 BGSAVE：LATENCY 记录 fork 事件 {latest['fork'][2] if 'fork' in latest else '-'}ms（160 MB 数据），SLOWLOG 没有对应命令；探测最多推迟 {s['fork']['delay'] / 1000:.1f}ms")
ss = list(csv.DictReader((out / "slow-subscriber.tsv").open(), delimiter="\t"))
peak = max(int(r["client_recent_max_output_buffer"]) for r in ss)
disc = int(ss[-1]["client_output_buffer_limit_disconnections"]) - int(ss[0]["client_output_buffer_limit_disconnections"])
mem = max(int(r["mem_clients_normal"]) for r in ss)
check(peak > 8 * 1024 * 1024 and disc >= 1,
      f"慢订阅者：单个客户端输出缓冲区最高 {peak / 1e6:.1f} MB，客户端内存最高 {mem / 1e6:.1f} MB，触发 client-output-buffer-limit 断开 {disc} 次；期间探测 p99 {s['slow_subscriber']['p99']}µs")
pl = {int(r["batch"]): r for r in csv.DictReader((out / "pipeline.tsv").open(), delimiter="\t")}
ops = {k: float(v["ops_per_second"]) for k, v in pl.items()}
check(ops[1000] > 5 * ops[1] and int(pl[1000]["batch_p50_us"]) > 10 * int(pl[1]["batch_p50_us"]),
      "pipeline：" + "；".join(f"每批 {k} 条 {ops[k]:,.0f} 次/秒、每批往返 p50 {pl[k]['batch_p50_us']}µs p99 {pl[k]['batch_p99_us']}µs" for k in sorted(pl)))

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
