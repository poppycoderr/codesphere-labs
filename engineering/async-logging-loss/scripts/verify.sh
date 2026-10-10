#!/usr/bin/env bash
# 异步日志在哪里丢：Logback AsyncAppender 的默认值、队列快满时丢弃 INFO、队列满时阻塞或丢弃、关闭时只等 maxFlushTime、调用位置、进程三种结束方式下写出的条数
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 30 秒。结论行是确定的；stop() 的耗时与各种退出方式下写出的条数另存在 timings.log，不参与比较
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS="ch.qos.logback:logback-classic:1.5.38 ch.qos.logback:logback-core:1.5.38 org.slf4j:slf4j-api:2.0.20"
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/AsyncLogLab.java 2>"$OUT/stderr.log" >"$f"
grep '^timing' "$OUT/stderr.log" >"$OUT/timings.log" || true; rm -f "$OUT/stderr.log"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 下游的卡住与放开由闩锁控制，结论行是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 logback=1.5.38
defaults	队列长度 256，丢弃阈值 51（剩余容量低于它时丢弃 INFO 及以下），neverBlock = false，关闭时最多等 1000 ms，记录调用位置 = false
discard.info	下游卡住期间记了 1001 条 INFO，全部立即返回；此时队列里有 206 条，剩余容量 50
discard.warn_fills	再记 50 条 WARN（不在丢弃之列）：队列里 256 条，剩余容量 0
block.error	队列满了之后再记一条 ERROR：业务线程 300 ms 内返回 = false
discard.written	下游恢复并正常关闭后，实际写出的：INFO 207，WARN 50，ERROR 1（记了 INFO 1001、WARN 50、ERROR 1）
never_block	neverBlock = true，下游卡住期间记 300 条 WARN 再记 50 条 ERROR：业务线程立即返回 = true；实际写出 INFO 1，WARN 256，ERROR 0
no_discard	discardingThreshold = 0，下游卡住期间记满队列后再记一条 INFO：业务线程 300 ms 内返回 = false；实际写出 INFO 258，WARN 0，ERROR 0
flush.default	队列里有 3000 条、下游每条 2 ms（写完要 6 秒）时关闭日志系统：stop() 在 0.9–1.5 秒内返回 = true，此时写出的不到一半 = true
flush.unbounded	maxFlushTime = 0（一直等到写完），500 条：写出 500
caller	默认配置下，下游拿到的调用方法名 = ?；includeCallerData = true 时 = main
exit_return	记 200 条日志（下游每条 5 ms）后main 方法直接返回：写出的不足 50 条 = true
exit_system_exit	记 200 条日志（下游每条 5 ms）后调用 System.exit(0)：写出的不足 50 条 = true
exit_stop_first	记 200 条日志（下游每条 5 ms）后退出前先调用 LoggerContext.stop()：200 条全部写出
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
