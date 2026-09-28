#!/usr/bin/env bash
# 容量拐点：MySQL 主键点查在 1—256 并发下的吞吐与 p99、主键更新的吞吐、同样 2 CPU 的 Redis GET 吞吐
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）、JDK 21 容器、一个 Redis 8.10.1 容器（2 CPU）；约 3 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-capacity-knee
REDIS_IMAGE="redis:8.10.1@sha256:8a1efc5f479551822b47424ccae982026b633f28818eab0387348120a61e10e2"
mysql_down "$PROJECT" >/dev/null 2>&1 || true
docker rm -f csl-capacity-redis >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
mysql_exec "$PROJECT" labs <schema/01-schema.sql
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
log "MySQL 点查与更新（约 2 分钟）"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/Capacity.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
log "Redis GET：redis-benchmark 在同一容器内压测，50 个连接"
docker run -d --name csl-capacity-redis --cpus 2 --memory 1g "$REDIS_IMAGE" redis-server --save "" --appendonly no >/dev/null
for i in $(seq 30); do [ "$(docker exec csl-capacity-redis redis-cli PING 2>/dev/null | tr -d '\r')" = PONG ] && break; sleep 0.5; done
docker exec csl-capacity-redis redis-benchmark -t set,get -n 500000 -c 50 -q 2>/dev/null | tr '\r' '\n' | grep -E "^(SET|GET):.*requests per second" >"$OUT/redis-benchmark.txt"
docker rm -f csl-capacity-redis >/dev/null
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest，2 CPU、1 GB）" "redis: 8.10.1（固定 digest，2 CPU）" "jdbc: $MYSQL_JDBC_JAR_COORD"
cat "$OUT/output.tsv" "$OUT/redis-benchmark.txt" >&2
python3 - "$OUT" <<'PY'
import re, sys
out = sys.argv[1]
t = open(f"{out}/output.tsv", encoding="utf-8").read()
rows = {(k, int(c)): (float(q.replace(",", "")), float(p50), float(p99)) for k, c, q, p50, p99 in
        re.findall(r"^(read|write)\.(\d+)\t.*?([\d,]+) QPS，p50 ([\d.]+) ms，p99 ([\d.]+) ms", t, re.M)}
reads = sorted((c, v) for (k, c), v in rows.items() if k == "read")
peak_c, peak = max(reads, key=lambda x: x[1][0])
top = reads[-1]
assert peak_c < top[0], reads                                   # 峰值不在最高并发：存在拐点
assert top[1][0] <= peak[0] * 1.05, reads                        # 拐点之后吞吐不再增长
assert top[1][2] > 4 * peak[2], reads                            # p99 继续上涨
w = max(v[0] for (k, c), v in rows.items() if k == "write")
assert w < peak[0] / 2, (w, peak)                                # 主键更新的吞吐远低于点查
r = open(f"{out}/redis-benchmark.txt", encoding="utf-8").read()
get = float(re.search(r"GET: ([\d.]+) requests per second", r)[1])
assert get > peak[0], (get, peak)
print(f"通过：点查峰值 {peak[0]:,.0f} QPS（并发 {peak_c}，p99 {peak[2]} ms）；并发 {top[0]} 时 {top[1][0]:,.0f} QPS、p99 {top[1][2]} ms；更新最高 {w:,.0f} QPS；Redis GET {get:,.0f} 次/秒")
PY
log "全部通过，输出在 $OUT（MySQL 容器仍在运行，make clean 删除）"
