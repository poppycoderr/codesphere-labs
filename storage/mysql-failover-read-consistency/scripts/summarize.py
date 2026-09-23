#!/usr/bin/env python3
"""对照客户端确认序列与新 source 上的数据，计算缺失、重复与各阶段耗时，断言文章中的关键结论。用法：summarize.py <输出目录>"""
import csv, re, sys
from datetime import datetime
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def t(s): return datetime.fromisoformat(s.replace("Z", "+00:00"))
def kv(name): return dict(l.split("\t", 1) for l in (out / name).read_text().splitlines() if "\t" in l)
def status(name): return dict(l.split("\t") for l in (out / name).read_text().splitlines())

def analyse(run):
    client = list(csv.DictReader((out / f"{run}-client.tsv").open(), delimiter="\t"))
    tl = {r[0]: (t(r[1]), r[2] if len(r) > 2 else "") for r in (l.split("\t") for l in (out / f"{run}-timeline.tsv").read_text().splitlines())}
    acked = {int(r["seq"]): t(r["utc"]) for r in client if r["event"] in ("client_ack", "first_write_after_failover", "retry_inserted", "retry_already_committed")}
    present = [int(x) for x in (out / f"{run}-new-source-seqs.txt").read_text().split()]
    missing = sorted(set(acked) - set(present))
    first = next(t(r["utc"]) for r in client if r["event"] == "first_write_after_failover")
    retry = next(r for r in client if r["event"].startswith("retry_"))
    fault = tl["fault_injected"][0]
    phases = dict(detect=(tl["detected"][0] - fault).total_seconds(), promote=(tl["promotion_done"][0] - tl["detected"][0]).total_seconds(),
                  route=(tl["routing_changed"][0] - tl["promotion_done"][0]).total_seconds(), first_write=(first - fault).total_seconds())
    return dict(acked=acked, present=present, missing=missing, dups=len(present) - len(set(present)), tl=tl, phases=phases, retry=retry)

runs = {r: analyse(r) for r in ["async-partition", "semisync-applier-paused", "semisync-timeout"]}
with open(out / "summary.md", "w") as f:
    f.write("<!-- 由 scripts/summarize.py 生成；缺失 = 客户端收到成功确认、但新 source 上不存在的序号 -->\n\n")
    f.write("| 场景 | 已确认写入 | 新 source 上存在 | 缺失 | 重复 | 故障→判定 | 判定→提升完成 | 提升→路由切换 | 故障→第一笔新写入 |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|\n")
    for r, a in runs.items():
        p = a["phases"]
        f.write(f"| `{r}` | {len(a['acked'])} | {len(a['present'])} | {len(a['missing'])} | {a['dups']} | {p['detect']:.1f}s | {p['promote']:.1f}s | {p['route']:.1f}s | {p['first_write']:.1f}s |\n")
print((out / "summary.md").read_text())

a = runs["async-partition"]
from datetime import timedelta
part, fault = a["tl"]["partition_started"][0], a["tl"]["fault_injected"][0]
check(len(a["missing"]) > 50, f"异步复制：分区 3 秒后 source 崩溃，{len(a['missing'])} 笔已确认写入在新 source 上不存在")
early = min(a["acked"][s] for s in a["missing"])
check(all(part - timedelta(seconds=0.5) <= a["acked"][s] <= fault for s in a["missing"]),
      f"缺失的写入都在分区开始前 0.5 秒之后、崩溃之前得到确认（最早一笔比分区开始早 {max(0, (part - early).total_seconds() * 1000):.0f}ms：已确认但还在复制途中）")
check(all(x["dups"] == 0 for x in runs.values()), "三个场景都没有重复写入（request_id 唯一）")
check(a["retry"]["event"] == "retry_inserted", "崩溃时正在执行的那一笔：新 source 上不存在，按同一 request_id 重试后写入")

rj = kv("rejoin-old-source.tsv")
old_only = rj["old_only_transactions"]
m = re.search(r":(\d+)-(\d+)", old_only)
check(rj["old_is_subset_of_new"].strip() == "0" and m and int(m.group(2)) - int(m.group(1)) + 1 == len(a["missing"]),
      f"旧 source 重启后多出 {old_only.split(':')[-1]} 这些事务，数量正好等于丢失的写入")
check("Replica_SQL_Running: No" in rj["after_rejoin_replica_status"], f"直接接回后 SQL 线程报错停止：{rj['after_rejoin_worker_error'][:110]}…")
check(rj["old_source_checksum"] != rj["new_source_checksum"], f"两边 orders 已经分叉：旧 source {rj['old_source_orders']} 行，新 source {rj['new_source_orders']} 行")

s = runs["semisync-applier-paused"]
st = status("semisync-applier-paused-semisync-status-before-fault.txt")
check(st["Rpl_semi_sync_source_status"] == "ON" and int(st["Rpl_semi_sync_source_yes_tx"]) > 100, f"半同步在故障前一直有效（yes_tx {st['Rpl_semi_sync_source_yes_tx']}，no_tx {st['Rpl_semi_sync_source_no_tx']}）")
naive = set(s["acked"]) - {int(x) for x in (out / "semisync-applier-paused-replica1-before-relay-apply-seqs.txt").read_text().split()}
check(len(naive) > 50, f"replica1 已 ACK 但暂停应用：如果不先应用 relay log 就提升，会缺 {len(naive)} 笔已确认写入")
check(len(s["missing"]) == 0, "先应用完已接收的 relay log 再提升：缺失 0 笔")
check("received_not_applied: " in (out / "semisync-applier-paused-candidates.txt").read_text()
      and re.search(r"received_not_applied: \S+:\d+-\d+", (out / "semisync-applier-paused-candidates.txt").read_text()) is not None,
      "候选比较时 replica1 的已接收集合大于已执行集合")

w = runs["semisync-timeout"]
st2 = status("semisync-timeout-semisync-status-before-fault.txt")
check(st2["Rpl_semi_sync_source_status"] == "OFF" and int(st2["Rpl_semi_sync_source_no_tx"]) > 0, f"两个 replica 都不可达、等待 2 秒超时后半同步退化为异步（no_tx {st2['Rpl_semi_sync_source_no_tx']}）")
check(len(w["missing"]) > 50, f"退化期间确认的写入在崩溃后缺失 {len(w['missing'])} 笔")

amb = [r for r in csv.DictReader((out / "ambiguous.tsv").open(), delimiter="\t")]
def e(table, event): return next(r["detail"] for r in amb if r["table"] == table and r["event"] == event)
check(all(any(r["table"] == tb and r["event"] == "client_timeout" for r in amb) for tb in ("orders", "orders_no_key")), "两笔写入都在等待半同步 ACK 时触发客户端 1 秒超时")
check(e("orders_no_key", "rows_for_request") == "2", "没有唯一键：按同一请求重试后出现 2 行（原请求其实已提交）")
check(e("orders", "retry") == "affected_rows=0" and e("orders", "rows_for_request") == "1", "有唯一 request_id：重试命中已存在的行，affected_rows = 0，仍是 1 行")

rd = {(r["strategy"], r["replica"]): r for r in csv.DictReader((out / "read-after-write.tsv").open(), delimiter="\t")}
d1 = rd[("replica-direct", "replica1")]
check(int(d1["stale_reads"]) > int(d1["attempts"]) // 2, f"复制正常的 replica1：写完立即读，{d1['stale_reads']}/{d1['attempts']} 次读不到")
check(all(int(r["stale_reads"]) == 0 for (k, rep), r in rd.items() if k in ("source-sticky", "gtid-wait")), "读 source、或在 replica 上等待写入的 GTID：全部读到")
g1, g2 = rd[("gtid-wait", "replica1")], rd[("gtid-wait", "replica2")]
check(float(g2["p50_read_ms"]) > 900, f"GTID 等待的代价：replica1 中位 {g1['p50_read_ms']}ms，延迟 1 秒的 replica2 中位 {g2['p50_read_ms']}ms")
check(rd[("replica-direct", "replica2")]["stale_reads"] == rd[("replica-direct", "replica2")]["attempts"], "延迟 1 秒的 replica2：直接读全部读不到")
if failures:
    sys.exit(f"{len(failures)} 项断言失败")
