"""把 GC 日志汇总成「键<TAB>事实」：每种配置跑 3 次，各项取中位数。参数：输出目录。"""
import re, statistics, sys
from pathlib import Path

out = Path(sys.argv[1])
lines = []
for name in ("xmx512m", "xmx2g", "xmx512m-pause50"):
    reps = []
    for rep in (1, 2, 3):
        log = (out / f"gc-{name}-{rep}.log").read_text()
        young = [float(x) for x in re.findall(r"Pause Young \([^)]*\) \([^)]*\) .*? ([\d.]+)ms$", log, re.M)]
        alloc = dict(l.split("\t") for l in (out / f"alloc-{name}-{rep}.txt").read_text().splitlines())
        reps.append(dict(n=len(young), avg=sum(young) / len(young), max=max(young), total=sum(young),
                         cycles=len(re.findall(r"Concurrent Mark Cycle [\d.]+ms", log)),
                         full=len(re.findall(r"Pause Full", log)), rate=int(alloc["rate_mb_per_s"])))
    m = {k: statistics.median(r[k] for r in reps) for k in reps[0]}
    per = "、".join(f"{r['avg']:.2f}" for r in reps)
    lines.append(f"gc.{name}\t3 次中位数，每次 8 秒、分配约 {m['rate']:,.0f} MB/s：Young GC {m['n']:.0f} 次，平均 {m['avg']:.2f} ms，最长 {m['max']:.2f} ms，"
                 f"合计 {m['total']:.0f} ms；并发标记周期 {m['cycles']:.0f} 次；Full GC {max(r['full'] for r in reps)} 次（3 次的平均暂停分别为 {per} ms）")
(out / "summary.tsv").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
