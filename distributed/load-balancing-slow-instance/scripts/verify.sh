#!/usr/bin/env bash
# 负载均衡遇到异常实例：10 个实例里 1 个变慢或快速失败时，轮询、随机、最少在途、两次随机选择与带摘除的最少在途各把多少请求送给它
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC  src/LbLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 固定种子的离散事件模拟，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
setup	10 个实例，每个 8 个处理槽、正常处理时间 10 ms（单实例上限 800 请求/秒）；到达速率 4000 请求/秒，模拟 30 秒；实例 3 异常：变慢时处理时间 100 ms（上限 80 请求/秒），快速失败时 1 ms 返回错误
slow.round_robin	送到异常实例 10.0%	p50 10 ms	p99 108279 ms	最大 120.3 s	超过 1 秒 9.92%	返回错误 0.00%
slow.random	送到异常实例 9.9%	p50 10 ms	p99 105608 ms	最大 117.6 s	超过 1 秒 9.79%	返回错误 0.00%
slow.least_in_flight	送到异常实例 1.3%	p50 10 ms	p99 100 ms	最大 0.1 s	超过 1 秒 0.00%	返回错误 0.00%
slow.two_choices	送到异常实例 1.8%	p50 10 ms	p99 100 ms	最大 0.2 s	超过 1 秒 0.00%	返回错误 0.00%
fail_fast.round_robin	送到异常实例 10.0%	p50 10 ms	p99 10 ms	最大 0.0 s	超过 1 秒 0.00%	返回错误 10.00%
fail_fast.random	送到异常实例 9.9%	p50 10 ms	p99 13 ms	最大 0.0 s	超过 1 秒 0.00%	返回错误 9.86%
fail_fast.least_in_flight	送到异常实例 42.7%	p50 10 ms	p99 10 ms	最大 0.0 s	超过 1 秒 0.00%	返回错误 42.72%
fail_fast.two_choices	送到异常实例 18.3%	p50 10 ms	p99 10 ms	最大 0.0 s	超过 1 秒 0.00%	返回错误 18.32%
fail_fast.least_in_flight_with_ejection	送到异常实例 0.0%	p50 10 ms	p99 10 ms	最大 0.0 s	超过 1 秒 0.00%	返回错误 0.03%	摘除 6 次
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
