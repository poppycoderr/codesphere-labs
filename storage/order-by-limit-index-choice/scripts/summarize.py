#!/usr/bin/env python3
"""汇总 run.sh 的输出并断言文章中的关键结论。用法：summarize.py <输出目录>"""
import csv, hashlib, json, re, statistics, sys
from pathlib import Path

out = Path(sys.argv[1])
rows, failures = [], []

def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok:
        failures.append(msg)

for d in sorted(p for p in out.glob("*/*") if p.is_dir()):
    samples = d.joinpath("explain-analyze.txt").read_text().split("-- sample ")[1:]
    times = []
    for s in samples:
        first = next(line for line in s.splitlines() if line.startswith("->"))
        times.append(float(re.search(r"actual time=[\d.e+]+\.\.([\d.e+]+)", first).group(1)))
    handler = dict(line.split("\t") for line in d.joinpath("handler-status.txt").read_text().splitlines())
    reads = sum(int(v) for k, v in handler.items())
    bp = dict(line.split("\t") for line in d.joinpath("buffer-pool.txt").read_text().splitlines())
    plan = json.loads(d.joinpath("explain.json").read_text())
    access = sorted(set(re.findall(r'"key": "([^"]+)"', json.dumps(plan))))
    ids = d.joinpath("result-ids.txt").read_text()
    rows.append({
        "group": d.parent.name, "variant": d.name,
        "access_keys": "+".join(access),
        "handler_reads": reads,
        "median_ms": round(statistics.median(times), 3),
        "min_ms": round(min(times), 3), "max_ms": round(max(times), 3),
        "samples": len(times),
        "buffer_pool_requests": int(bp["Innodb_buffer_pool_read_requests_delta"]),
        "physical_reads": int(bp["Innodb_buffer_pool_reads_delta"]),
        "result_rows": len(ids.splitlines()),
        "result_sha1": hashlib.sha1(ids.encode()).hexdigest()[:12],
        "first_line": samples[0].splitlines()[1].strip(),
    })

with open(out / "summary.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=[k for k in rows[0] if k != "first_line"], extrasaction="ignore", lineterminator="\n")
    w.writeheader(); w.writerows(rows)

with open(out / "summary.md", "w") as f:
    f.write("<!-- 由 scripts/summarize.py 生成；耗时为 EXPLAIN ANALYZE 根节点 actual time 的结束值 -->\n\n")
    f.write("| 分组 | 查询 | 使用的索引 | Handler 读取次数 | 中位数 ms | 最小 ms | 最大 ms | 缓冲池页请求 | 物理读 | 返回行数 | 结果 SHA-1 |\n|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|\n")
    for r in rows:
        f.write(f"| {r['group']} | `{r['variant']}` | {r['access_keys']} | {r['handler_reads']:,} | {r['median_ms']} | {r['min_ms']} | {r['max_ms']} | {r['buffer_pool_requests']:,} | {r['physical_reads']} | {r['result_rows']} | `{r['result_sha1']}` |\n")

by = {r["variant"]: r for r in rows}
orig, union = by["00-original"], by["04-union-all"]
check("using PRIMARY" in orig["first_line"] or "PRIMARY" in orig["access_keys"], "原查询沿主键扫描（PRIMARY）")
check(orig["handler_reads"] > 2_000_000, f"原查询读取超过 200 万次（实际 {orig['handler_reads']:,}）")
for v in ["01-force-index", "02-prefer-ordering-index-off", "03-order-by-expression", "04-union-all"]:
    check(by[v]["access_keys"] == "idx_state_event_deleted", f"{v} 使用 idx_state_event_deleted")
    check(by[v]["result_sha1"] == orig["result_sha1"], f"{v} 返回的 100 个 id 与原查询完全相同")
    check(by[v]["median_ms"] * 10 < orig["median_ms"], f"{v} 的中位耗时不到原查询的十分之一")
check(union["handler_reads"] < 1000, f"UNION ALL 读取次数少于 1,000（实际 {union['handler_reads']}）")
check(union["median_ms"] * 100 < by["01-force-index"]["median_ms"] * 10, "UNION ALL 比 FORCE INDEX 至少快一个数量级")
head = by["10-control-processed-at-head"]
check(head["access_keys"] == "PRIMARY" and head["handler_reads"] < 1000, "数据在表头时，主键扫描只读几百行")
check(by["11-control-single-value"]["access_keys"] == "idx_state_event_deleted", "单个等值条件时优化器直接选择二级索引")

check(all(r["physical_reads"] == 0 for r in rows), "预热后所有查询都没有物理读（热缓存）")

if failures:
    sys.exit(f"{len(failures)} 项断言失败")
