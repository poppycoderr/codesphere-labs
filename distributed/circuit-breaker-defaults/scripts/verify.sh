#!/usr/bin/env bash
# 熔断器的默认配置：最少调用数、打开后的拒绝、半开状态的放行数与卡住、哪些异常算失败、慢调用、多个接口共用一个熔断器、十个实例坏一个
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS="io.github.resilience4j:resilience4j-circuitbreaker:2.4.0 io.github.resilience4j:resilience4j-core:2.4.0 org.slf4j:slf4j-api:2.0.20"
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/BreakerLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 resilience4j=2.4.0
defaults.window	滑动窗口 COUNT_BASED，大小 100，最少调用数 100
defaults.thresholds	失败率阈值 50.0%，慢调用阈值 60 秒，慢调用比例阈值 100.0%
defaults.open	打开后等待 60 秒，到时自动转半开 = false
defaults.half_open	半开时放行 10 次，半开状态最长停留 0 秒（0 表示不限）
minimum.99	默认配置，连续失败 99 次：CLOSED（窗口内 99 次，失败 99，慢 0，失败率 -1.0）
minimum.100	第 100 次失败：OPEN（窗口内 100 次，失败 100，慢 0，失败率 100.0）
open.reject	打开期间调用 50 次：下游实际被调用 0 次，50 次抛 CallNotPermittedException
dilute.49	先成功 100 次，随后连续失败 49 次：CLOSED（窗口内 100 次，失败 49，慢 0，失败率 49.0）
dilute.50	第 50 次连续失败：OPEN（窗口内 100 次，失败 50，慢 0，失败率 50.0）
half_open.lazy	等待时间（200 ms）过去 400 ms 后、没有新调用时的状态：OPEN
half_open.permits	此时同时来 15 个调用（都还没返回）：放行 10 个，拒绝 5 个，状态 HALF_OPEN
half_open.stuck	这 10 个调用一直不返回，再过 400 ms：状态 HALF_OPEN，新的调用被放行 = false
half_open.result_5_of_10	10 个试探调用里 5 个失败：OPEN
half_open.result_4_of_10	再次半开，10 个试探调用里 4 个失败：CLOSED
exceptions.default	默认配置，100 次调用里 60 次抛业务异常（余额不足），下游本身正常：OPEN（窗口内 100 次，失败 60，慢 0，失败率 60.0）
exceptions.ignored	ignoreExceptions(BusinessException)：CLOSED（窗口内 40 次，失败 0，慢 0，失败率 -1.0）
exceptions.record_only	recordExceptions(IOException)：CLOSED（窗口内 100 次，失败 0，慢 0，失败率 0.0）
slow.default	默认配置，100 次调用每次 30 秒才成功返回：CLOSED（窗口内 100 次，失败 0，慢 0，失败率 0.0）
slow.tuned	慢调用阈值 2 秒、比例阈值 50%：OPEN（窗口内 100 次，失败 0，慢 100，失败率 0.0）
shared.one_breaker	导出接口全部失败、查询接口全部成功，各 50 次，共用一个熔断器：OPEN（窗口内 100 次，失败 50，慢 0，失败率 50.0）；查询接口的下一次调用被放行 = false
shared.per_endpoint	各用各的熔断器，各 100 次：导出 OPEN，查询 CLOSED
one_of_ten	按服务名建的熔断器，10 个实例里 1 个全部失败：CLOSED（窗口内 100 次，失败 10，慢 0，失败率 10.0）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
