#!/usr/bin/env python3
"""汇总 Cluster 实验：路由与跨 slot、MOVED 与 ASK、迁移期间的业务错误、大 key 迁移、倾斜与故障提升。用法：summarize.py <输出目录>"""
import csv, re, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def kv(name):
    d = {}
    for l in (out / name).read_text().splitlines():
        if "\t" in l:
            k, v = l.split("\t", 1); d.setdefault(k, v)
    return d
def rows(name):
    return list(csv.DictReader((out / name).open(), delimiter="\t"))

sl = [l.split("\t") for l in (out / "slots.tsv").read_text().splitlines()]
slot = {r[1]: r[2] for r in sl if r[0] == "keyslot"}
cross = next(r[1] for r in sl if r[0].startswith("redis-cli_mget"))
check(slot["user:1001:profile"] != slot["user:1001:orders"] and slot["{user:1001}:profile"] == slot["{user:1001}:orders"] and cross.startswith("CROSSSLOT"),
      f"user:1001:profile 在 slot {slot['user:1001:profile']}、user:1001:orders 在 slot {slot['user:1001:orders']}，MGET 返回 CROSSSLOT；加 hash tag 后都在 slot {slot['{user:1001}:profile']}")
cs = kv("crossslot.tsv")
check("same hashslot" in cs["mget_different_slots"] and "same hashslot" in cs["eval_different_slots"] and cs["mget_hash_tag"] == "[p, o]" and cs["eval_hash_tag"] == "[p, o]",
      f"JedisCluster 在客户端就拒绝跨 slot 的 MGET 与 EVAL（{cs['mget_different_slots'][:70]}），hash tag 的写法正常返回")
mv = kv("moved.tsv")
check(mv["reply_without_-c"].startswith(f"MOVED {mv['slot']} "), f"向不负责 slot {mv['slot']} 的 {mv['asked']} 发 GET：{mv['reply_without_-c'].split()[0]} {mv['slot']} <{mv['owner']} 的地址>")

mg = kv("migrate.tsv")
check(mg["get_migrated_key_at_source"].startswith(f"ASK {mg['slot']} ") and mg["get_migrated_key_at_target_without_asking"].startswith(f"MOVED {mg['slot']} ")
      and mg["get_migrated_key_at_target_with_asking"].startswith("OK v"),
      f"迁移 slot {mg['slot']}（{mg['source']} → {mg['target']}）途中：向源节点读已搬走的 key 返回 ASK；直接问目标返回 MOVED（回到源）；先发 ASKING 再读，目标返回值")
check(mg["keys_after_source"] == "0" and mg["keys_after_target"] == mg["keys_before"] and mg["get_at_old_owner_after"].startswith("MOVED"),
      f"{mg['batches']} 批 MIGRATE 后源节点剩 0 个、目标 {mg['keys_after_target']} 个 key；之后再问旧节点得到 MOVED")
def workload(name, start, end):
    w = rows(f"{name}-workload.tsv"); errs = rows(f"{name}-errors.tsv")
    t0 = int((out / f"{name}-workload.tsv").stat().st_mtime)  # 仅用于提示
    during = w  # 每秒一行，整段都统计
    return sum(int(r["ops"]) for r in w), sum(int(r["errors"]) for r in w), sum(int(r["wrong_values"]) for r in w), max(int(r["p99_us"]) for r in w), max(int(r["max_us"]) for r in w), errs
ops, err, wrong, p99, mx, _ = workload("migrate", mg["started_at_ms"], mg["finished_at_ms"])
check(err == 0 and wrong == 0, f"手动迁移期间 JedisCluster 共 {ops:,} 次读写，错误 0、读到错误值 0；每秒 p99 最高 {p99}µs，单次最长 {mx / 1000:.1f}ms")
rs = kv("reshard.tsv")
ops2, err2, wrong2, p992, mx2, _ = workload("reshard", rs["started_at_ms"], rs["finished_at_ms"])
chk = (out / "reshard-check.txt").read_text()
check(rs["keys_before"] == rs["keys_after"] and err2 == 0 and wrong2 == 0 and "All 16384 slots covered" in chk,
      f"redis-cli --cluster reshard 迁移 1000 个 slot（{rs['from']} → {rs['to']}，{(int(rs['finished_at_ms']) - int(rs['started_at_ms'])) / 1000:.1f}s）：key 总数 {rs['keys_before']} → {rs['keys_after']}，JedisCluster {ops2:,} 次读写零错误，每秒 p99 最高 {p992}µs")

bk = (out / "big-key-migrate.tsv").read_text()
mig_us = int(re.search(r"slowlog_on_source\t\S+ \S+ (\d+) MIGRATE", bk).group(1))
probe_max = max(float(x) for x in re.findall(r"source_probe\t[\d.]+ ([\d.]+) [\d.]+ \d+", bk))
mem = int(re.search(r"big_hash_memory_usage\t(\d+)", bk).group(1))
check(mig_us > 50_000 and probe_max > 0.5 * mig_us / 1000 and "big_hash_on_target\t500000" in bk,
      f"迁移一个 50 万字段、{mem / 1e6:.1f} MB 的 Hash：SLOWLOG 记录 MIGRATE {mig_us / 1000:.0f}ms，源节点上的 PING 最长 {probe_max:.0f}ms；目标收到全部 500,000 个字段")

sk = [r for r in rows("skew.tsv") if r["node"].startswith("n")]
hot = dict(l.split("\t")[i:i + 2] for l in (out / "skew.tsv").read_text().splitlines() if l.startswith("hot_slot") for i in range(0, 6, 2))
by = {r["node"]: r for r in sk}
others = [int(r["dbsize"]) for r in sk if r["node"] != hot["hot_owner"]]
check(hot["hot_slot_keys"] == "50000" and int(by[hot["hot_owner"]]["dbsize"]) > 2 * max(others),
      "slot 与 key 的分布：" + "；".join(f"{r['node']} {r['slots']} 个 slot、{int(r['dbsize']):,} 个 key、{int(r['used_memory']) / 1e6:.1f} MB" for r in sk) + f"；slot {hot['hot_slot']} 一个 slot 就有 50,000 个 key")

fo = kv("failover.tsv"); t0 = int(fo["killed_at_ms"])
st = rows("failover-state.tsv")
fail_seen = [int(r["at_ms"]) for r in st if r["cluster_state"] == "fail"]
flag_fail = next((int(r["at_ms"]) for r in st if "fail" in r["victim_flags"] and "fail?" not in r["victim_flags"]), None)
ok_again = next((int(r["at_ms"]) for r in st if fail_seen and int(r["at_ms"]) > fail_seen[-1] and r["cluster_state"] == "ok"), None)
w = rows("failover-writes.tsv"); final = {int(x) for x in (out / "failover-final-seqs.txt").read_text().split()}
acked = [r for r in w if r["status"] == "ack"]; lost = [r for r in acked if int(r["seq"]) not in final]
errs = [r for r in w if r["status"] == "error"]
after_errs = [int(r["at_ms"]) for r in errs if int(r["at_ms"]) >= t0]
last_err = max(after_errs) if after_errs else t0
nodes_after = (out / "failover-nodes-after.txt").read_text()
slow_w = [int(r["latency_us"]) for r in acked if int(r["at_ms"]) >= t0]
slow_n = sum(1 for x in slow_w if x > 1_000_000); slow_max = max(slow_w) if slow_w else 0
check(flag_fail and (flag_fail - t0) >= 4000 and len(lost) <= 5 and re.search(r"fail", nodes_after),
      f"primary {fo['victim']} 被 SIGKILL（cluster-node-timeout 5000）：{(flag_fail - t0) / 1000:.1f}s 后被标记为 fail，"
      + (f"集群状态 fail 持续到 {(fail_seen[-1] - t0) / 1000:.1f}s，" if fail_seen else "")
      + f"写入错误 {len(errs)} 次；JedisCluster 在内部重试，{slow_n} 次写入被卡住超过 1 秒，最长 {slow_max / 1e6:.1f}s；已确认 {len(acked):,} 条，丢失 {len(lost)} 条")
if errs: print("信息：失败写入的错误类型：" + "；".join(sorted({re.sub(r"[0-9.:]+", "#", r["detail"])[:80] for r in errs})[:4]))

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
