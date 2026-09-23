#!/usr/bin/env python3
"""汇总两边的计时与读取量，断言结果一致与数量级关系。用法：summarize.py <输出目录>"""
import csv, statistics, sys
from pathlib import Path

out = Path(sys.argv[1])
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)

rows = {}
for d in sorted((out / "queries").iterdir()):
    my = [float(x) for x in d.joinpath("mysql-millis.txt").read_text().split()]
    ch = list(csv.DictReader(d.joinpath("clickhouse-query-log.tsv").open(), delimiter="\t"))
    same = d.joinpath("mysql-result.tsv").read_text() == d.joinpath("clickhouse-result.tsv").read_text()
    rows[d.name] = dict(my_med=statistics.median(my), my_min=min(my), my_max=max(my),
                        ch_med=statistics.median(int(r["query_duration_ms"]) for r in ch),
                        ch_min=min(int(r["query_duration_ms"]) for r in ch), ch_max=max(int(r["query_duration_ms"]) for r in ch),
                        read_rows=int(ch[0]["read_rows"]), read_bytes=int(ch[0]["read_bytes"]), same=same)
with open(out / "summary.md", "w") as f:
    f.write("<!-- 由 scripts/summarize.py 生成；7 次采样，MySQL 为会话内 NOW(6) 差值，ClickHouse 为 query_log.query_duration_ms -->\n\n")
    f.write("| 查询 | MySQL 中位 ms（最小—最大） | ClickHouse 中位 ms（最小—最大） | ClickHouse 读取行数 | 读取字节 | 结果一致 |\n|---|---:|---:|---:|---:|---|\n")
    for q, r in rows.items():
        f.write(f"| `{q}` | {r['my_med']:.2f}（{r['my_min']:.2f}—{r['my_max']:.2f}） | {r['ch_med']}（{r['ch_min']}—{r['ch_max']}） | {r['read_rows']:,} | {r['read_bytes'] / 1e6:.1f}MB | {'是' if r['same'] else '否'} |\n")
print((out / "summary.md").read_text())

for q, r in rows.items():
    check(r["same"], f"{q}：MySQL 与 ClickHouse 返回的结果逐字节相同")
q1, q2, q3 = rows["q1-status-aggregate"], rows["q2-monthly-range"], rows["q3-point-lookup"]
check(q1["my_med"] > 20 * max(q1["ch_med"], 1), f"全表聚合：MySQL {q1['my_med']:.0f}ms，ClickHouse {q1['ch_med']}ms")
check(q2["my_med"] > 20 * max(q2["ch_med"], 1), f"范围聚合：MySQL {q2['my_med']:.0f}ms，ClickHouse {q2['ch_med']}ms")
check(q3["my_med"] < 5 and q3["read_rows"] > 100_000, f"点查：MySQL {q3['my_med']:.2f}ms；ClickHouse {q3['ch_med']}ms，但读了 {q3['read_rows']:,} 行")
if failures:
    sys.exit(f"{len(failures)} 项断言失败")
