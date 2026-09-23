#!/usr/bin/env python3
"""汇总 ImportBench 的输出：每种方式取 3 次采样的中位数，断言相对关系与失败语义。用法：summarize.py <输出目录>"""
import statistics, sys
from collections import defaultdict
from pathlib import Path

out = Path(sys.argv[1])
lines = (out / "import-bench.txt").read_text().splitlines()
failures = []
def check(ok, msg):
    print(("通过：" if ok else "失败：") + msg)
    if not ok: failures.append(msg)

ms, rows = defaultdict(list), {}
for l in lines:
    f = l.split("\t")
    if len(f) == 5 and f[0] != "mode":
        ms[f[0]].append(int(f[3])); rows[f[0]] = int(f[1])
med = {m: statistics.median(v) for m, v in ms.items()}
rps = {m: rows[m] * 1000 / med[m] for m in med}
base = rps["row-by-row-autocommit"]
with open(out / "summary.md", "w") as f:
    f.write("<!-- 由 scripts/summarize.py 生成；耗时为 3 次采样的中位数 -->\n\n| 方式 | 行数 | 中位耗时 ms | 行/秒 | 相对逐行插入 | 三次采样 ms |\n|---|---:|---:|---:|---:|---|\n")
    for m in med:
        f.write(f"| `{m}` | {rows[m]:,} | {med[m]:.0f} | {rps[m]:,.0f} | {rps[m] / base:.0f}× | {', '.join(map(str, ms[m]))} |\n")
print((out / "summary.md").read_text())

check(rps["batch-1000-no-rewrite"] > 3 * base, "只用批处理（不开启重写）已比逐行自动提交快数倍：省掉了每行一次提交")
check(rps["batch-1000-rewrite"] > 3 * rps["batch-1000-no-rewrite"], "开启 rewriteBatchedStatements 后再快数倍：一批一次往返")
check(rps["batch-5000-rewrite"] < 1.5 * rps["batch-1000-rewrite"], "批大小 1000 → 5000 提升有限（不到 1.5 倍）")
check(rps["4-threads-batch-1000-rewrite"] > 1.2 * rps["batch-1000-rewrite"], "4 个线程比单线程更快")
check(rps["load-data-local-infile"] > rps["batch-1000-rewrite"], "LOAD DATA LOCAL INFILE 快于单线程重写批处理")

fail = {l.split("\t")[1]: l for l in lines if l.startswith("failure")}
check("update_counts=[1, 1, 1, -3, 1, 1]" in fail["rewrite=false"] and "committed=[1, 2, 3, 5, 6]" in fail["rewrite=false"],
      "未开启重写：逐条执行，失败的第 4 行为 -3，其余 5 行提交后写入")
check("update_counts=[-3, -3, -3, -3, -3, -3]" in fail["rewrite=true"] and "committed=[]" in fail["rewrite=true"],
      "开启重写：整批是一条多行 INSERT，全部 -3，一行都没有写入")
if failures:
    sys.exit(f"{len(failures)} 项断言失败")
