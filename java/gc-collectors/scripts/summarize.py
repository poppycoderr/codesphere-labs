"""从 GC 日志与程序输出汇总「键<TAB>事实」。参数：输出目录。

停顿：G1 与 Parallel 取 -Xlog:gc 中以 Pause 开头、以毫秒结尾的行；ZGC 取 -Xlog:gc+phases 中的 Pause 行。
只统计测量窗口（预热之后）内的停顿。
"""
import re, statistics, sys
from pathlib import Path

out = Path(sys.argv[1])
PAUSE = re.compile(r"^\[(\d+\.\d+)s\].*?\bGC\(\d+\) (?:[yYO]: )?(Pause [^\n]*?) ([\d.]+)ms$", re.M)
STALL = re.compile(r"^\[(\d+\.\d+)s\].*Allocation Stall \([^)]*\) ([\d.]+)ms$", re.M)


def kv(p):
    return dict(l.split("\t") for l in p.read_text().splitlines() if "\t" in l)


def window(log, start_ms, end_ms, rx):
    return [m for m in rx.finditer(log) if start_ms / 1000 <= float(m.group(1)) <= end_ms / 1000]


rows = []
for f in sorted(out.glob("run-*.txt")):
    name = f.stem[4:]
    v = kv(f)
    log = (out / f"gc-{name}.log").read_text()
    start = int(v["measure_from_uptime_ms"])
    end = start + int(v["measure_ms"])
    pauses = window(log, start, end, PAUSE)
    ms = [float(m.group(3)) for m in pauses]
    kinds = {}
    for m in pauses:
        k = re.sub(r" \d+M->.*$| \(G1 [^)]*\)$", "", m.group(2)).strip()
        kinds.setdefault(k, []).append(float(m.group(3)))
    stalls = [float(m.group(2)) for m in window(log, start, end, STALL)]
    full = sum(len(x) for k, x in kinds.items() if k.startswith("Pause Full"))
    stall = (f"；分配停顿 {len(stalls)} 次，合计 {sum(stalls) / 1000:.1f} s，中位数 {statistics.median(stalls):.1f} ms，"
             f"最长 {max(stalls):.1f} ms") if stalls else "；分配停顿 0 次"
    rows.append(f"run.{name}\t吞吐 {int(v['ops_per_s']) // 1000}k 次/秒，CPU {v['cpu_cores']} 核，停顿 {len(ms)} 次，合计 {sum(ms):.0f} ms，"
                f"最长 {max(ms):.2f} ms，Full GC {full} 次，探针 p99 {v['probe_p99_ms']} ms、p99.9 {v['probe_p999_ms']} ms、最大 {v['probe_max_ms']} ms{stall}")
    rows.append(f"kinds.{name}\t" + "；".join(f"{k} {len(x)} 次，最长 {max(x):.2f} ms" for k, x in sorted(kinds.items())))
for f in sorted(out.glob("explicit-*.txt")):
    name = f.stem[9:]
    v = kv(f)
    log = (out / f"gc-explicit-{name}.log").read_text()
    lo, hi = int(v["call_from_uptime_ms"]), int(v["call_to_uptime_ms"])
    pauses = window(log, lo, hi, PAUSE)
    first = next((l for l in log.splitlines() if "System.gc()" in l), "无")
    first = re.sub(r"^\[[^\]]*\]\[[^\]]*\]\[[^\]]*\] GC\(\d+\) ", "", first)
    first = re.sub(r" \d+M->.*$", "", first)
    rows.append(f"explicit.{name}\t日志：{first}；调用期间停顿 {len(pauses)} 次，合计 {sum(float(m.group(3)) for m in pauses):.1f} ms；System.gc() 返回耗时 {v['call_ms']} ms")
(out / "summary.tsv").write_text("\n".join(rows) + "\n")
print("\n".join(rows))
