#!/usr/bin/env bash
# Reactor 的信号契约：终态、取消、request 额度、预取、flatMap 的顺序与并发、组装期与订阅期（JDK 25 + Reactor 3.8.8）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，Reactor 3.8.8 的 jar 从 Maven Central 下载到 $LABS_CACHE；约 15 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
CP=""; while read -r c; do CP="$CP:/cache/m2/$(basename "$(maven_jar "$c")")"; done < deps.txt
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$IMG" java -cp "${CP#:}" src/Lab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG" "jars: $(tr '\n' ' ' < deps.txt)"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
terminal.rogue	发布者在 onComplete 之后又发了 3 和一个错误：订阅者收到 [next 10, next 20, complete]；被丢弃并交给钩子的 [3, late]
terminal.create	Flux.create 里 complete 之后再 next(2)：订阅者收到 [next 1, complete, complete 之后 isCancelled=false]
cancel.take	无限的 generate 后接 take(3)：得到 [0, 1, 2]，上游一共生成 3 个，上游看到 [cancel]
error.terminal	range(1, 10) 的 map 在第 3 个元素抛异常：下游收到 1、2 和错误，map 执行了 3 次，上游看到 [cancel]（剩下 7 个不再处理）
request.additive	订阅时连续 request(2)、request(3)，之后不再请求：上游收到请求 [2, 3]，订阅者收到 5 个后停住
prefetch.publishOn	publishOn 之后下游只 request(1)：publishOn 向上游请求 [256]，上游已经发出 256 个（在 publishOn 的队列里等着）
prefetch.limitRate	limitRate(10)，下游无界请求：上游收到的请求依次为 [10, 8, 8, 8, 8, 8]
order.flatMap	元素 1、2、3 各自延迟 3、2、1 秒：flatMap 输出 [3, 2, 1]
order.concatMap	同样的输入：concatMap 输出 [1, 2, 3]
concurrency.flatMap	6 个各需 1 秒的内层，flatMap(f, 2)：同时活跃的内层最多 2 个（用 doFinally 减计数时读到 3 个），虚拟时间共 3 秒
concurrency.flatMap	6 个各需 1 秒的内层，flatMap(f, 6)：同时活跃的内层最多 6 个（用 doFinally 减计数时读到 6 个），虚拟时间共 1 秒
concurrency.concatMap	6 个各需 1 秒的内层，concatMap：同时活跃的内层最多 1 个（用 doFinally 减计数时读到 2 个），虚拟时间共 6 秒
lazy.just	Mono.just(f())：还没订阅时 f 已执行 1 次，订阅两次后共 1 次（两次订阅拿到同一个值）
lazy.defer	Mono.defer(() -> Mono.just(f()))：订阅前 0 次，订阅两次后 2 次
lazy.subscribe	组装了 doOnNext 却没有订阅：副作用执行 0 次（FluxPeekFuseable）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
