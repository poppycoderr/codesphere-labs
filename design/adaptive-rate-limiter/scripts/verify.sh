#!/usr/bin/env bash
# 限流组件：三种算法的窗口边界、本地并发、多实例共享 Redis 的原子性与配额平分、规则热更新、Redis 暂停时的三种降级策略
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 Redis 容器与 JDK 21 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
CID=$("${C[@]}" ps -aq redis)
JARS=""
for coord in redis.clients:jedis:5.2.0 org.apache.commons:commons-pool2:2.12.0 org.json:json:20240303 com.google.code.gson:gson:2.11.0 org.slf4j:slf4j-api:1.7.36; do
  JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"
done
log "运行 src/RateLimit.java；降级场景前由本脚本暂停 Redis"
java_in_network "$CID" -cp "${JARS#:}" src/RateLimit.java "$OUT" >"$OUT/output.tsv" 2>"$OUT/client.log" & JAVA=$!
for i in $(seq 600); do [ -f "$OUT/outage.ready" ] && break; sleep 0.1; done
docker pause "$CID" >/dev/null; touch "$OUT/outage.paused"
wait "$JAVA" || { docker unpause "$CID" >/dev/null; cat "$OUT/client.log"; fail "RateLimit.java 运行失败"; }
docker unpause "$CID" >/dev/null
rm -f "$OUT/outage.ready" "$OUT/outage.paused"
grep -v '^SLF4J' "$OUT/client.log" >"$OUT/client.log.tmp" || true; mv "$OUT/client.log.tmp" "$OUT/client.log"
{ "${C[@]}" config --images | sed 's/^/image: /'; echo "jdk_image: $JDK_IMAGE"; echo "jedis: 5.2.0"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）" "jedis: 5.2.0"

expect_line "$OUT/output.tsv" "algorithm.fixed-window	第 990ms 与第 1000ms 各到达 100 个：放行 200，任意 1 秒内最多放行 200" "固定窗口：边界两侧各放行一整份"
expect_line "$OUT/output.tsv" "algorithm.sliding-log	第 990ms 与第 1000ms 各到达 100 个：放行 100，任意 1 秒内最多放行 100" "滑动日志：任意 1 秒不超过限额"
expect_line "$OUT/output.tsv" "algorithm.token-bucket	第 990ms 与第 1000ms 各到达 100 个：放行 101，任意 1 秒内最多放行 101" "令牌桶：突发受容量约束"
expect_regex "$OUT/output.tsv" "local.check-then-increment	6400 个请求、限额 1000：放行 (100[1-9]|10[1-9][0-9]|1[1-9][0-9]{2}|[2-9][0-9]{3})" "先检查后递增：并发下超发"
expect_line "$OUT/output.tsv" "local.compare-and-set	6400 个请求、限额 1000：放行 1000" "比较并交换：正好 1000"
expect_regex "$OUT/output.tsv" "distributed.get-then-incr	16 个实例、2000 个请求、全局限额 1000：放行 (100[1-9]|10[1-9][0-9]|1[1-9][0-9]{2})" "GET 后 INCR：多实例下超发"
expect_line "$OUT/output.tsv" "distributed.lua	16 个实例、2000 个请求、全局限额 1000：放行 1000" "Lua 原子脚本：正好 1000"
expect_line "$OUT/output.tsv" "distributed.split_quota	每个实例本地限额 250，流量按 70%/10%/10%/10% 分到 4 个实例：放行 850 / 2000（全局容量 1000），热点实例拒绝 1150" "平分配额：流量不均时少放行"
expect_line "$OUT/output.tsv" "拒绝版本 2（limit=-5），保留版本 1" "非法规则被拒绝"
expect_line "$OUT/output.tsv" "reload.effect	同一窗口 1500 个请求：放行 800" "规则更新后按新限额判断"
expect_line "$OUT/output.tsv" "outage.fail-open	Redis 暂停期间 2000 个请求：放行 2000" "放行策略：全部放行"
expect_line "$OUT/output.tsv" "outage.fail-closed	Redis 暂停期间 2000 个请求：放行 0" "拒绝策略：全部拒绝"
expect_line "$OUT/output.tsv" "outage.local-fallback	Redis 暂停期间 2000 个请求：放行 1000" "本地兜底：每个实例 250"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
