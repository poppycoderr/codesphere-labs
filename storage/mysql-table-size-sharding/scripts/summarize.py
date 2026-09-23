#!/usr/bin/env python3
"""汇总 verify.sh 的输出并断言文章中的关键结论。用法：summarize.py <输出目录>"""
import csv, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)
def tsv(name): return list(csv.DictReader((out / name).open(), delimiter="\t"))

bt = {r["table"]: {k: (int(v) if v.isdigit() else v) for k, v in r.items()} for r in tsv("btree.tsv")}
check(bt["orders"]["tree_height"] == 2, f"10 万行 orders：2 层，根页 {bt['orders']['root_records']} 条记录 = 叶子页数")
for t in ["orders_big", "narrow_20m", "wide_1m"]:
    check(bt[t]["tree_height"] == 3, f"{t}（{bt[t]['rows']:,} 行，平均行长 {bt[t]['avg_row_bytes']} 字节）：3 层")
fanouts = {t: bt[t]["leaf_pages"] / bt[t]["root_records"] for t in ["orders_big", "narrow_20m", "wide_1m"]}
check(all(850 < f < 950 for f in fanouts.values()), "三张 3 层表的中间页扇出：" + "、".join(f"{t} {f:.0f}" for t, f in fanouts.items()))
fan = sum(fanouts.values()) / 3
for t in ["narrow_20m", "wide_1m", "orders_big"]:
    cap = fan * fan * bt[t]["rows_per_leaf"]
    print(f"信息：按平均扇出 {fan:.0f} 推算，{t} 每页 {bt[t]['rows_per_leaf']} 行，3 层约可容纳 {cap / 1e4:,.0f} 万行")
check(fan * fan * bt["narrow_20m"]["rows_per_leaf"] > 10 * bt["narrow_20m"]["rows"], "2,000 万行窄表的 3 层容量超过当前行数 10 倍")

pl = {(r["table"], r["pass"]): r for r in tsv("point-lookups.tsv")}
hot2, hot3 = float(pl[("orders", "hot")]["avg_micros"]), float(pl[("narrow_20m", "hot")]["avg_micros"])
check(int(pl[("narrow_20m", "hot")]["physical_reads"]) == 0 and int(pl[("orders", "hot")]["physical_reads"]) == 0, "热轮没有物理读")
check(hot3 - hot2 < 5, f"页都在内存时：2 层 {hot2}µs，3 层 {hot3}µs，多一层只差 {hot3 - hot2:.2f}µs")
cold3 = float(pl[("narrow_20m", "cold")]["avg_micros"])
check(cold3 > 3 * hot3, f"同一批主键第一次查询（{pl[('narrow_20m', 'cold')]['physical_reads']} 次物理读）平均 {cold3}µs，是热轮的 {cold3 / hot3:.1f} 倍")

ddl = {r["operation"].split(",")[0].split("（")[0]: int(r["micros"]) for r in tsv("ddl.tsv")}
inst, idx, copy, cnt = ddl["ADD COLUMN note VARCHAR(20) NULL"], ddl["ADD INDEX idx_k (k)"], ddl["MODIFY v BIGINT NOT NULL"], ddl["SELECT COUNT(*)"]
check(inst < 100_000, f"INSTANT 加列 {inst / 1000:.0f}ms")
check(idx > 3_000_000, f"INPLACE 加二级索引 {idx / 1e6:.1f}s")
check(copy > 2 * idx, f"改列类型复制整表 {copy / 1e6:.1f}s")
check("ALGORITHM=INPLACE is not supported" in (out / "ddl-inplace-rejected.txt").read_text(), "改列类型不支持 INPLACE")
print(f"信息：SELECT COUNT(*) {cnt / 1e6:.2f}s")

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
