#!/usr/bin/env bash
# 基于 Redis 的分布式信号量：获取、等待超时、释放与重复释放、租约回收、BRPOP 与 ZADD 之间崩溃、并发压力、租约早于任务结束
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 Redis 容器与 1 个 JDK 21 容器；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null
CID=$("${C[@]}" ps -q redis)
log "运行 src/Semaphore.java"
java_in_network "$CID" src/Semaphore.java "$OUT" >"$OUT/run.log" 2>&1 || { cat "$OUT/run.log"; fail "Semaphore.java 运行失败"; }
{ "${C[@]}" config --images | sed 's/^/image: /'; echo "cpus: 2"; echo "mem_limit: 1g"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）" "jdk_image: $JDK_IMAGE"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
