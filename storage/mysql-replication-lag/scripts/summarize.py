#!/usr/bin/env python3
"""汇总复制延迟实验的时间线与对照结果，断言文章中的关键结论。用法：summarize.py <输出目录>"""
import csv, re, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def samples(name, replica):
    return [r for r in csv.DictReader((out / f"{name}-samples.tsv").open(), delimiter="\t") if r["replica"] == replica and r["seq_gap"] != ""]
def num(v):
    try: return int(v)
    except (TypeError, ValueError): return None

# 稳态
base = samples("baseline", "replica1") + samples("baseline", "replica2")
check(max(int(r["seq_gap"]) for r in base) <= 3, f"稳态：每 50ms 一条心跳，两个 replica 的业务序号差最大 {max(int(r['seq_gap']) for r in base)} 条")

# 大事务
ev = (out / "big-transaction-events.tsv").read_text()
src_ms = int(re.search(r"source_millis=(\d+)", ev).group(1))
big = samples("big-transaction", "replica1")
peak = max(big, key=lambda r: int(r["seq_gap"]))
check(int(peak["seq_gap"]) > 20 and int(peak["received_not_applied"]) >= int(peak["seq_gap"]),
      f"大事务（source 上执行 {src_ms}ms）提交后：replica1 业务序号最多落后 {peak['seq_gap']} 条，其中 {peak['received_not_applied']} 个事务已经收到、尚未应用")
check(all(r["io_service_state"] == "ON" and r["sql_running"] == "Yes" for r in big), "大事务期间 IO 与 SQL 线程一直正常运行")
check(max(int(r["applying_trx_age_ms"]) for r in big) > 2000, f"正在应用的事务距 source 提交最长 {max(int(r['applying_trx_age_ms']) for r in big)}ms（applier 状态表）")
after = [r for r in big if int(r["mono_ms"]) > int(peak["mono_ms"]) and r["seq_gap"] == "0"]
check(bool(after), "大事务应用完后，积压的心跳很快追平")
print(f"信息：大事务期间 Seconds_Behind_Source 取值 {sorted({r['seconds_behind_source'] for r in big})}")

# 并行回放
par = {(r["workload"], int(r["replica_parallel_workers"])): r for r in csv.DictReader((out / "parallel.tsv").open(), delimiter="\t")}
i1, i4 = int(par[("independent", 1)]["catch_up_millis"]), int(par[("independent", 4)]["catch_up_millis"])
h1, h4 = int(par[("hot-row", 1)]["catch_up_millis"]), int(par[("hot-row", 4)]["catch_up_millis"])
check(i4 * 1.5 < i1, f"独立行：1 个 worker 追平 20,000 个事务 {i1 / 1000:.1f}s，4 个 worker {i4 / 1000:.1f}s")
dist = [int(x) for x in par[("independent", 4)]["transactions_per_worker"].split(",")]
check(len(dist) == 4 and min(dist) > 4000, f"独立行：4 个 worker 分到的事务 {dist}")
check(abs(h4 - h1) < 0.2 * h1, f"同一热点行：1 个 worker {h1 / 1000:.1f}s，4 个 worker {h4 / 1000:.1f}s，没有收益")
hd = [int(x) for x in par[("hot-row", 4)]["transactions_per_worker"].split(",")]
check(hd[0] > 0.99 * sum(hd), f"同一热点行：4 个 worker 分到的事务 {hd}，几乎全部落在一个 worker 上")

busy = list(csv.DictReader((out / "busy-replica.tsv").open(), delimiter="\t"))
b0, b1 = int(busy[0]["catch_up_millis"]), int(busy[1]["catch_up_millis"])
check(b1 > 1.5 * b0, f"replica1 上同时运行两个全表查询：追平 {b0 / 1000:.1f}s → {b1 / 1000:.1f}s")

# 链路静默中断
net = dict(l.split("\t")[:2] for l in (out / "stall-network.tsv").read_text().splitlines())
st = samples("stall", "replica2")
cut, back = net["network_disconnected"], net["network_reconnected"]
silent = [r for r in st if r["utc"] > cut and r["io_service_state"] == "ON" and r["utc"] < back]
check(len(silent) > 0 and all(r["seconds_behind_source"] == "0" for r in silent),
      f"断开复制网络后，IO 状态仍为 ON、Seconds_Behind_Source 仍为 0 的采样持续到 {silent[-1]['utc'][11:19] if silent else '-'}（断开于 {cut[11:19]}）")
stale = max(int(r["seq_gap"]) for r in silent)
check(stale > 100, f"同一时段业务序号最多落后 {stale} 条心跳（约 {stale * 0.05:.0f} 秒）")
conn = [r for r in st if r["io_service_state"] == "CONNECTING"]
check(bool(conn) and all(r["seconds_behind_source"] in ("null", "None", "") for r in conn), "超过 replica_net_timeout 后才变为 CONNECTING，Seconds_Behind_Source 变为 NULL")
check("Replica_IO_Running: Yes" in (out / "stall-replica2-status-after.txt").read_text(), "网络恢复后 IO 线程重新连接")

rows = [l.split("\t") for l in (out / "final-checksums.tsv").read_text().splitlines()]
check(len({tuple(r[1:]) for r in rows}) == 1, "最终三个节点的 GTID 集合与三张表的 CHECKSUM 完全相同")
if failures:
    sys.exit(f"{len(failures)} 项断言失败")
