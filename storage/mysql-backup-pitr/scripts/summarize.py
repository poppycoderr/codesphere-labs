#!/usr/bin/env python3
"""断言误删恢复演练的关键结论：恢复结果与对照实例一致、两个失败路径被发现。用法：summarize.py <输出目录>"""
import re, sys
from datetime import datetime
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def checks(name): return {l.split("\t")[0]: (int(l.split("\t")[1]), int(l.split("\t")[2])) for l in (out / name).read_text().splitlines()}
def kv(name): return dict(l.split("\t", 1) for l in (out / name).read_text().splitlines() if "\t" in l and not l.startswith("#"))
def business(c): return {k: v for k, v in c.items() if not k.startswith("invariant")}
def invariants_ok(c): return all(v[0] == 0 for k, v in c.items() if k.startswith("invariant"))

bm = kv("backup-manifest.tsv")
check(bm["exit_code"] == "0" and "GTID_PURGED" in bm["gtid_purged"], f"全量备份退出码 0，记录了 GTID_PURGED 与 binlog 起点；SHA-256 {bm['sha256'][:12]}…")

src, ctrl_final, ctrl_backup = checks("source-after-incident-checks.tsv"), checks("control-final-checks.tsv"), checks("control-at-backup-checks.tsv")
bad_rows = int((out / "bad-delete.tsv").read_text().split("\t")[2])
check(src["order_items"][0] < ctrl_final["order_items"][0] and src["invariant_order_total_mismatch"][0] > 0,
      f"误删后 source：order_items 剩 {src['order_items'][0]} 行，{src['invariant_order_total_mismatch'][0]} 个订单金额与明细不符，{src['invariant_inventory_mismatch'][0]} 个 SKU 库存与明细不符")

base = checks("restore-baseline-checks.tsv")
check(business(base) == business(ctrl_backup) and invariants_ok(base), "隔离实例导入全量备份后，业务校验和与对照实例在备份时刻完全相同")

bad_gtid = (out / "bad-transaction.tsv").read_text().splitlines()[1].split("\t")[0]
before = checks("restore-before-bad-checks.tsv")
markers = [l.split("\t") for l in (out / "markers-around-incident.tsv").read_text().splitlines()]
bad_at = datetime.fromisoformat((out / "bad-delete.tsv").read_text().split("\t")[1])
committed_before = sum(1 for _, ts in markers if datetime.fromisoformat(ts) < bad_at)
check(before["orders"][0] == 1200 + committed_before and invariants_ok(before),
      f"UNTIL SQL_BEFORE_GTIDS = {bad_gtid.split(':')[1]}：停在误删之前，订单 {before['orders'][0]} 笔（误删前已提交的合法写入全部在），不变量全部成立")
same_second = [s for s, ts in markers if ts[:19] == str(bad_at)[:19]]
after_in_second = [s for s, ts in markers if ts[:19] == str(bad_at)[:19] and datetime.fromisoformat(ts) > bad_at]
check(len(after_in_second) > 0 and len(same_second) > len(after_in_second),
      f"误删提交于 {str(bad_at)[11:]}；同一秒内还有订单 {same_second[0]}—{same_second[-1]}，其中 {len(after_in_second)} 笔在误删之后：按秒截断无法把它们分开")

final = checks("restore-final-checks.tsv")
check(business(final) == business(ctrl_final) and invariants_ok(final), f"跳过误删事务、补回之后的写入：{final['orders'][0]} 笔订单，四张表的业务校验和与对照实例完全相同")
check((out / "restore-final-gtid.txt").read_text().strip() == (out / "source-gtid-executed.txt").read_text().strip(), "恢复后的 GTID 集合与 source 相同（误删事务以空事务占位）")
lost_if_stop = ctrl_final["orders"][0] - before["orders"][0]
print(f"信息：如果只恢复到误删之前，会丢掉之后的 {lost_if_stop} 笔合法订单；跳过误删并继续重放后为 0")

tl = {l.split("\t")[0]: datetime.fromisoformat(l.split("\t")[1]) for l in (out / "timeline.tsv").read_text().splitlines()}
s0 = tl["restore_started"]
print(f"信息：恢复耗时（演练数据很小，不能外推）：可读 {(tl['restore_readable'] - s0).total_seconds():.1f}s，可校验 {(tl['restore_verified'] - s0).total_seconds():.1f}s，可切换 {(tl['restore_switchable'] - s0).total_seconds():.1f}s")

cb = kv("failure-corrupted-backup.tsv")
check(cb["expected_sha256"] != cb["actual_sha256"] and "拒绝恢复" in cb.get("gate", ""), "备份损坏一个字节：SHA-256 不一致，恢复流程在导入前停止")
cc = checks("failure-corrupted-backup-checks.tsv")
check(cb["import_exit_code"].strip() == "0" and business(cc) != business(ctrl_backup) and cc["invariant_inventory_mismatch"][0] > 0,
      f"如果跳过校验：导入退出码 0，但库存校验和不同，{cc['invariant_inventory_mismatch'][0]} 个 SKU 不满足不变量")

gap = kv("failure-binlog-gap.tsv")
gc = checks("failure-binlog-gap-checks.tsv")
check(gap["applier_errors"].strip() == "0" and gap["target_is_subset"].strip() == "0",
      f"归档缺少 {gap['missing_binlog_file']}：重放没有报错，但目标 GTID 集合不完整，缺 {gap['missing_gtids']}")
check(gc["orders"][0] < ctrl_final["orders"][0] and business(gc) != business(ctrl_final),
      f"缺口的结果：订单 {gc['orders'][0]} 笔（应为 {ctrl_final['orders'][0]}），业务校验和不同")
if failures:
    sys.exit(f"{len(failures)} 项断言失败")
