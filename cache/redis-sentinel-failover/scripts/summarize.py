#!/usr/bin/env python3
"""汇总 Sentinel 故障切换实验：每个场景的时间线、已确认写入的丢失与重复，断言文章中的关键结论。用法：summarize.py <输出目录>"""
import csv, re, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def tsv(name):
    p = out / name
    return list(csv.DictReader(p.open(), delimiter="\t")) if p.exists() else []

# 场景零：部分同步与全量同步
rs = {l.split("\t")[0]: l for l in (out / "resync.tsv").read_text().splitlines()}
def delta(line, k):
    b = int(re.search(rf"before=.*?{k}:(\d+)", line).group(1)); a = int(re.search(rf"after=.*?{k}:(\d+)", line).group(1))
    return a - b
big, small = rs["resync-backlog-1mb"], rs["resync-backlog-16kb"]
check(delta(big, "sync_partial_ok") == 1 and delta(big, "sync_full") == 0, "replica 暂停，被 source 断开后又写入 200 KB，恢复后重连（backlog 1 MB）：部分同步 1 次，全量同步 0 次")
check(delta(small, "sync_full") == 1 and delta(small, "sync_partial_ok") == 0, "同样的断线与写入量、backlog 只有 16 KB：断线期间的写入超出 backlog，全量同步 1 次")

def analyze(name):
    writes = tsv(f"{name}-writes.tsv")
    events = tsv(f"{name}-client.tsv") + [{"at_ms": r["at_ms"], "event": "script:" + r["event"], "detail": r.get("detail", "")} for r in csv.DictReader((out / f"{name}-script.tsv").open(), delimiter="\t", fieldnames=["at_ms", "event", "detail"])]
    sentinel = tsv(f"{name}-sentinel.tsv")
    final = {int(x) for x in (out / f"{name}-final-seqs.txt").read_text().split()}
    acked = [w for w in writes if w["status"] == "ack"]
    lost = [w for w in acked if int(w["seq"]) not in final]
    unconfirmed = [w for w in writes if w["status"] == "unconfirmed"]
    errors = [w for w in writes if w["status"] in ("error", "io_error", "skipped")]
    kept_unacked = [w for w in writes if w["status"] != "ack" and int(w["seq"]) in final]
    return writes, events, sentinel, final, acked, lost, unconfirmed, errors, kept_unacked

def first(rows, pred, key="at_ms"):
    for r in rows:
        if pred(r): return int(r[key])
    return None

def timeline(name):
    writes, events, sentinel, final, acked, lost, unconfirmed, errors, kept = analyze(name)
    t0 = first(events, lambda e: e["event"] == "script:fault_injected")
    new = (out / f"{name}-final-primary.txt").read_text().strip()
    sdown = first(sentinel, lambda s: s["channel"] == "+sdown" and s["message"].startswith("master"))
    odown = first(sentinel, lambda s: s["channel"] == "+odown")
    switch = first(sentinel, lambda s: s["channel"] == "+switch-master")
    last_old = max((int(w["at_ms"]) for w in acked if w["host"] == "r1"), default=None)
    first_new = min((int(w["at_ms"]) for w in writes if w["host"] == new and w["status"] in ("ack", "unconfirmed")), default=None)
    rel = lambda t: f"{(t - t0) / 1000:+.2f}s" if t else "-"
    print(f"信息：{name} 时间线（相对故障注入）：+sdown {rel(sdown)}，+odown {rel(odown)}，+switch-master {rel(switch)}，"
          f"旧 primary 上最后一次确认 {rel(last_old)}，客户端在 {new} 上首次写入成功 {rel(first_new)}")
    lines = [f"t0\tfault_injected\t{t0}", f"t1\tlast_client_ack_on_old_primary\t{last_old}", f"t2\tsdown\t{sdown}", f"t3\todown\t{odown}",
             f"t4\tswitch_master\t{switch}\t{new}", f"t5\tfirst_write_on_new_primary\t{first_new}",
             f"t6\tverification\tacked={len(acked)} lost_acked={len(lost)} unconfirmed={len(unconfirmed)} errors={len(errors)} present_but_not_acked={len(kept)}"]
    (out / f"{name}-timeline.tsv").write_text("\n".join(lines) + "\n")
    return dict(t0=t0, sdown=sdown, odown=odown, switch=switch, last_old=last_old, first_new=first_new, acked=acked, lost=lost,
                unconfirmed=unconfirmed, errors=errors, kept=kept, writes=writes, new=new)

k = timeline("kill-primary")
check(k["switch"] and 3000 <= k["sdown"] - k["t0"] <= 6000 and k["first_new"] > k["odown"],
      f"primary 被 SIGKILL：{(k['sdown'] - k['t0']) / 1000:.1f}s 后主观下线（down-after-milliseconds 3000），{(k['switch'] - k['t0']) / 1000:.1f}s 完成切换，客户端 {(k['first_new'] - k['t0']) / 1000:.1f}s 恢复写入")
check(len(k["lost"]) <= 5, f"primary 被 SIGKILL：已确认 {len(k['acked']):,} 条，丢失 {len(k['lost'])} 条（复制几乎没有积压）；故障期间失败 {len(k['errors'])} 次")
old = (out / "kill-primary-replication-after.txt").read_text()
check(re.search(r"## r1\nrole:slave", old) is not None, "旧 primary 重启后被 Sentinel 改为 replica")

a = timeline("partition-async")
lost_on_r1 = [w for w in a["lost"] if w["host"] == "r1"]
check(len(a["lost"]) > 200 and len(lost_on_r1) == len(a["lost"]),
      f"分区（异步复制）：旧 primary 在分区后仍确认写入直到 {(a['last_old'] - a['t0']) / 1000:.1f}s，已确认的 {len(a['lost'])} 条写入全部丢失（都写在旧 primary 上），分区恢复后旧 primary 变为 replica")
ra = (out / "partition-async-replication-after.txt").read_text()
check(re.search(r"## r1\nrole:slave", ra) is not None, "分区恢复后旧 primary 被改为新 primary 的 replica，它独有的写入被丢弃")

m = timeline("partition-min-replicas")
nore = [w for w in m["writes"] if w["status"] == "error" and "NOREPLICAS" in w["detail"]]
check(nore and 0 < len(m["lost"]) < len(a["lost"]),
      f"分区 + min-replicas-to-write 1、max-lag 2：旧 primary 从 {(int(nore[0]['at_ms']) - m['t0']) / 1000:.1f}s 起拒绝写入（NOREPLICAS，共 {len(nore)} 次），丢失的已确认写入从 {len(a['lost'])} 条降到 {len(m['lost'])} 条，但不是 0")

w = timeline("partition-wait")
w_lost_unconfirmed = [x for x in w["unconfirmed"] if int(x["seq"]) not in {int(y) for y in (out / "partition-wait-final-seqs.txt").read_text().split()}]
check(len(w["lost"]) == 0 and len(w["unconfirmed"]) > 0,
      f"分区 + 每次写入后 WAIT 1 100：WAIT 确认的写入丢失 0 条；分区期间 {len(w['unconfirmed'])} 条写入 WAIT 返回 0（未确认），其中 {len(w_lost_unconfirmed)} 条最终不存在")
def lat(rows, host=None):
    v = sorted(int(r["latency_us"]) for r in rows if r["status"] == "ack" and (host is None or r["host"] == host))
    return v[len(v) // 2], v[int(len(v) * 0.99)]
a50, a99 = lat([r for r in a["writes"] if int(r["at_ms"]) < a["t0"]])
w50, w99 = lat([r for r in w["writes"] if int(r["at_ms"]) < w["t0"]])
print(f"信息：分区前的写入延迟：SET p50 {a50}µs、p99 {a99}µs；SET + WAIT 1 p50 {w50}µs、p99 {w99}µs")
for name, r in (("kill-primary", k), ("partition-async", a), ("partition-min-replicas", m), ("partition-wait", w)):
    print(f"信息：{name}：已确认 {len(r['acked'])}，丢失 {len(r['lost'])}，未确认 {len(r['unconfirmed'])}，失败 {len(r['errors'])}，未确认但最终存在 {len(r['kept'])}")

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
