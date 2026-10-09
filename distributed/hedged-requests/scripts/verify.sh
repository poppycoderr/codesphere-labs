#!/usr/bin/env bash
# 备份请求：2% 的请求偶发变慢时，不发备份、30 毫秒后发备份、带 5% 预算、立即发两份，在两种负载下的延迟分位与额外负载
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC  src/HedgeLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 固定种子的离散事件模拟，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
setup	10 个实例各 8 个处理槽；98% 的请求处理 10 ms，2% 处理 300 ms，平均 15.8 ms，总容量约 5060 请求/秒；模拟 30 秒
rate_2500.none	p50 10 ms	p99 300 ms	p99.9 300 ms	备份请求占 0.0%	服务端实际执行次数是请求数的 1.00 倍	两份都执行完的请求占 0.0%
rate_2500.hedge_30ms	p50 10 ms	p99 40 ms	p99.9 40 ms	备份请求占 1.9%	服务端实际执行次数是请求数的 1.02 倍	两份都执行完的请求占 1.9%
rate_2500.hedge_30ms_budget_5	p50 10 ms	p99 40 ms	p99.9 40 ms	备份请求占 1.9%	服务端实际执行次数是请求数的 1.02 倍	两份都执行完的请求占 1.9%	因预算用完而没发的备份 0 次
rate_2500.hedge_immediately	p50 33 ms	p99 131 ms	p99.9 186 ms	备份请求占 100.0%	服务端实际执行次数是请求数的 2.00 倍	两份都执行完的请求占 100.0%
rate_4500.none	p50 10 ms	p99 300 ms	p99.9 306 ms	备份请求占 0.0%	服务端实际执行次数是请求数的 1.00 倍	两份都执行完的请求占 0.0%
rate_4500.hedge_30ms	p50 10894 ms	p99 22430 ms	p99.9 22928 ms	备份请求占 97.9%	服务端实际执行次数是请求数的 1.98 倍	两份都执行完的请求占 97.9%
rate_4500.hedge_30ms_budget_5	p50 12 ms	p99 162 ms	p99.9 386 ms	备份请求占 4.9%	服务端实际执行次数是请求数的 1.05 倍	两份都执行完的请求占 4.9%	因预算用完而没发的备份 29090 次
rate_4500.hedge_immediately	p50 11391 ms	p99 22958 ms	p99.9 23312 ms	备份请求占 100.0%	服务端实际执行次数是请求数的 2.00 倍	两份都执行完的请求占 100.0%
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
