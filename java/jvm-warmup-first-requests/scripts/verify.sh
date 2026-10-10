#!/usr/bin/env bash
# 进程刚启动时的前几个请求：同一个处理函数在新 JVM 里第 1、2—10、91—100、901—1000、9901—10000 次调用的耗时，默认参数、-Xint、只用 C1、AOT 缓存、先预热再接流量
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，限 2 个 CPU；约 1 分钟。计时类实验，只断言倍数关系，运行期间不要同时跑其他负载
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
# 先编译成 jar：AOT 缓存只收录从 jar 加载的类。父进程依次做训练运行、再用每组参数各启动 5 个子进程取中位数
docker run --rm --cpus 2 -v "$PWD:/w" -w /w "$J25" sh -c 'mkdir -p /tmp/c && javac -d /tmp/c src/WarmupLab.java && jar cf /tmp/app.jar -C /tmp/c . && java -cp /tmp/app.jar WarmupLab' 2>/dev/null | grep -v 'checksum' >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "container_cpus: 2"
cat "$f" >&2

val() { awk -F'\t' -v k="$1" '$1==k {print $2}' "$f"; }
mul() { awk -v a="$1" -v b="$2" 'BEGIN{print a*b}'; }
ge() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a>=b)}' || fail "$3（$1 < $2）"; log "通过：$3（$1 ≥ $2）"; }
le() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a<=b)}' || fail "$3（$1 > $2）"; log "通过：$3（$1 ≤ $2）"; }
steady=$(val default.calls_9901_10000_us)
ge "$(val default.call_1_us)" "$(mul "$steady" 100)" "默认参数：第 1 次调用比稳定后慢 100 倍以上"
ge "$(val default.calls_2_10_us)" "$(mul "$steady" 5)" "默认参数：第 2—10 次调用比稳定后慢 5 倍以上"
ge "$(val default.calls_91_100_us)" "$(mul "$steady" 2)" "默认参数：第 91—100 次调用仍比稳定后慢 2 倍以上"
ge "$(val xint.calls_9901_10000_us)" "$(mul "$steady" 10)" "只用解释器：一万次之后仍比默认参数慢 10 倍以上"
le "$(val aot_cache.call_1_us)" "$(mul "$(val default.call_1_us)" 0.85)" "AOT 缓存：第 1 次调用的耗时不超过默认的 85%"
le "$(val aot_cache.launch_to_first_response_ms)" "$(mul "$(val default.launch_to_first_response_ms)" 0.9)" "AOT 缓存：从启动到第一个响应不超过默认的 90%"
le "$(val prewarm.call_1_us)" "$(mul "$(val default.call_1_us)" 0.05)" "先预热 3000 次：第一个真实请求的耗时不到默认第 1 次调用的 5%"
ge "$(val prewarm.launch_to_first_response_ms)" "$(val default.launch_to_first_response_ms)" "先预热：从启动到第一个响应更晚"
log "全部通过，输出在 $OUT"
