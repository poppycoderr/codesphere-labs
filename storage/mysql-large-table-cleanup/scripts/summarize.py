#!/usr/bin/env python3
"""汇总 verify.sh 的输出并断言文章中的关键结论。用法：summarize.py <输出目录>"""
import re, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def text(name): return (out / name).read_text()
def num(pattern, t): return int(re.search(pattern, t).group(1))
def probes(name):
    return {l.split("\t")[0]: l.split("\t")[1] for l in text(name).splitlines() if not l.startswith("#") and "\t" in l}

one = text("one-shot-delete.txt")
one_rows, one_us = map(int, re.search(r"^(\d+)\t(\d+)$", one, re.M).groups())
one_bytes, one_tx = num(r"binlog_bytes\t(\d+)", one), num(r"transactions\t(\d+)", one)
check(one_rows == 1_000_000 and one_tx == 1, f"一次性删除：100 万行、1 个事务，耗时 {one_us / 1e6:.2f}s，binlog {one_bytes / 1e6:.1f}MB")

batch = text("batch-delete.txt")
b_total = num(r"\|\s+(\d+) \|\n\+-+\+\n\+-+\+-+", batch)
b_batches, b_non_empty, b_deleted, b_max, b_avg = map(int, re.search(r"\|\s+(\d+) \|\s+(\d+) \|\s+(\d+) \|\s+(\d+) \|\s+(\d+) \|", batch).groups())
b_bytes, b_tx = num(r"binlog_bytes\t(\d+)", batch), num(r"transactions\t(\d+)", batch)
check(b_deleted == 1_000_000 and b_non_empty == 100 and b_tx == 100, f"分批删除：100 批、100 个事务，共 {b_deleted:,} 行")
check(abs(b_bytes - one_bytes) / one_bytes < 0.01, f"binlog 总量与一次性删除相同（{b_bytes / 1e6:.1f}MB 对 {one_bytes / 1e6:.1f}MB），每批约 {b_bytes / 100 / 1e6:.2f}MB")
check(b_max * 10 < one_us, f"单批最长 {b_max / 1000:.0f}ms，不到一次性删除（{one_us / 1000:.0f}ms）的十分之一；总耗时 {b_total / 1e6:.2f}s")

p_idx, p_hint, p_none = probes("lock-probe-indexed.txt"), probes("lock-probe-index-hint.txt"), probes("lock-probe-no-index.txt")
check("\tALL\t" in text("lock-probe-indexed.txt"), "删除 31 万行（约 10%）时，优化器放弃 idx_created，选择全表扫描")
check(all(v == "ERROR 1205" for v in p_idx.values()), "全表扫描的删除：范围内外的更新与插入全部阻塞")
check("\trange\t" in text("lock-probe-index-hint.txt"), "加 INDEX 提示后：range 扫描 idx_created")
def get(p, key): return next(v for k, v in p.items() if key in k)
check(get(p_hint, "WHERE id = 100") == "ERROR 1205" and get(p_hint, "'2025-01-15") == "ERROR 1205", "走索引：范围内的更新与插入阻塞")
check(get(p_hint, "WHERE id = 2000000").startswith("完成") and get(p_hint, "'2025-09-01").startswith("完成"), "走索引：范围外的更新与远处插入立即完成")
check(get(p_hint, "'2025-02-01 00:00:00'").startswith("完成"), "走索引：在范围边界 2025-02-01 00:00:00 插入立即完成")
check(all(v == "ERROR 1205" for v in p_none.values()), "条件列没有索引：范围外的更新与远处插入也阻塞")

fs = text("file-size.txt").splitlines()
sizes = [int(l.split()[-1]) for l in fs if l.startswith("labs/")]
check(sizes[0] == sizes[1], f"删除 100 万行后文件大小不变（{sizes[0] / 1048576:.0f}MB）")
opt_us = num(r"optimize_micros\t(\d+)", text("file-size.txt"))
check(sizes[2] < sizes[1] * 0.85, f"OPTIMIZE TABLE 后 {sizes[2] / 1048576:.0f}MB，耗时 {opt_us / 1e6:.1f}s")
check("doing recreate + analyze instead" in text("optimize-message.txt"), "InnoDB 提示 OPTIMIZE 以重建表代替")

rng = text("range-delete.txt")
r_batches, r_empty, r_deleted = map(int, re.findall(r"\|\s+(\d+) \|\s+(\d+) \|\s+(\d+) \|\s*$", rng, re.M)[-1])
check(r_batches == 301 and r_empty == 200, f"表尾一条补写数据把主键区间拉到 300 万：{r_batches} 批，其中 {r_empty} 批为空")

dp = text("drop-partition.txt")
before, after, drop_us = num(r"binlog_position_before\t(\d+)", dp), num(r"binlog_position_after\t(\d+)", dp), num(r"drop_micros\t(\d+)", dp)
check(num(r"rows_in_p2025q1 \|\n\+-+\+\n\|\s+(\d+)", dp) == 1_000_000, "分区 p2025q1 有 100 万行")
check(after - before < 1024 and "DROP PARTITION" in dp, f"DROP PARTITION：binlog 位点 {before} → {after}，只记录一条 DDL")
check(drop_us < 1_000_000, f"DROP PARTITION 耗时 {drop_us / 1000:.0f}ms")

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
