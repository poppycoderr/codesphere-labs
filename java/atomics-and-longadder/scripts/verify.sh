#!/usr/bin/env bash
# volatile 上的 i++ 丢更新、CAS 重试让计算函数执行多次、节点复用下的 ABA、计数吞吐、用 LongAdder.sum() 做限额会超发
# 约 20 秒，会占满 8 个线程；吞吐数字只用于同一次运行内的相对比较
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
java src/Atomics.java >"$OUT/output.tsv"
write_environment "$OUT/environment.txt"
f="$OUT/output.tsv"
expect_line "$f" "AtomicReference：线程 1 的 CAS 成功，栈变成 [B, C]（已被弹出的 B 回到了栈顶）" "普通引用发生 ABA"
expect_line "$f" "AtomicStampedReference：线程 1 的 CAS 失败（版本 0 → 当前 3），栈仍是 [A, C]" "带版本号的引用拒绝 ABA"
expect_line "$f" "VarHandle.compareAndSet(0 → 1)：成功 1 个，字段值 1" "VarHandle CAS 只有一个线程成功"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
num = lambda s: int(s.replace(",", ""))
v = [(num(a), num(b)) for a, b in re.findall(r"volatile long 为 ([\d,]+)（丢失 [\d,]+），AtomicLong 为 ([\d,]+)", t)]
assert len(v) == 3 and all(a < 8_000_000 and b == 8_000_000 for a, b in v), v
print(f"通过：volatile i++ 3 轮都丢更新 {[8_000_000 - a for a, _ in v]}，AtomicLong 都是 8,000,000")
m = re.search(r"成功 ([\d,]+) 次，计算函数执行 ([\d,]+) 次.*副作用执行 ([\d,]+) 次，最终余额 ([\d,]+)", t)
ok, calls, side, bal = map(num, m.groups())
assert calls > ok and side == calls and bal == ok == 800_000, m.groups()
print(f"通过：CAS 成功 {ok:,} 次，计算函数执行 {calls:,} 次")
tp = {int(n): dict((k, num(x)) for k, x in re.findall(r"(\w+) ([\d,]+) 万次/秒", line)) for n, line in re.findall(r"^throughput\.(\d)\t(.*)$", t, re.M)}
assert tp[8]["LongAdder"] > 5 * tp[8]["AtomicLong"], tp[8]
assert tp[8]["AtomicLong"] < tp[1]["AtomicLong"], (tp[1], tp[8])
print(f"通过：8 线程 LongAdder {tp[8]['LongAdder']:,} 万次/秒，AtomicLong {tp[8]['AtomicLong']:,}；AtomicLong 单线程 {tp[1]['AtomicLong']:,}")
lim = [(num(a), num(b)) for a, b in re.findall(r"LongAdder 先 sum\(\) 再 increment\(\) 最终 ([\d,]+)，AtomicLong 条件 CAS 最终 ([\d,]+)", t)]
assert len(lim) == 3 and all(b == 1000 for _, b in lim) and any(a > 1000 for a, _ in lim), lim
print(f"通过：限额 1000，LongAdder 检查后递增 {[a for a, _ in lim]}，AtomicLong 条件 CAS 都是 1000")
PY
log "全部通过，输出在 $OUT"
