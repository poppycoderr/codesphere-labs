#!/usr/bin/env bash
# Redis 原子性、事务与锁：名额扣减的五种写法、MULTI 的入队错误与运行时错误、WATCH 冲突、重启后 EVALSHA 与 FCALL、
# 锁的 token 释放、租约过期后旧持有者继续写、fencing token 让下游拒绝旧写入
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 Redis 容器与 JDK 21 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null
run() { java_in_network "$("${C[@]}" ps -q redis)" src/Atomicity.java "$1" "$OUT" >"$OUT/$1.log" 2>&1 || { cat "$OUT/$1.log"; fail "Atomicity.java $1 失败"; }; }
log "名额扣减、事务与锁"
run main
log "重启前加载 Function 与 EVAL 脚本，重启后再调用"
run before-restart
"${C[@]}" restart redis >/dev/null 2>&1
for i in $(seq 30); do [ "$("${C[@]}" exec -T redis redis-cli PING | tr -d '\r')" = PONG ] && break; sleep 0.5; done
run after-restart
"${C[@]}" exec -T redis redis-cli INFO server | tr -d '\r' | grep -E '^redis_version:' >"$OUT/server.txt"
{ "${C[@]}" config --images | sed 's/^/image: /'; echo "cpus: 2"; echo "mem_limit: 1g"; echo "appendonly: yes（everysec）"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）" "jdk_image: $JDK_IMAGE"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
