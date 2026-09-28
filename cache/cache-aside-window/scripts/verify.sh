#!/usr/bin/env bash
# 缓存一致性的窗口：Cache Aside 脏回填、事务内删缓存、延迟双删的快慢两种情况、写入标记、不加控制的自然并发；
# 以及不一致率指标的两种 Micrometer gauge 写法
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Redis 与 MySQL 容器各 2 CPU、1 GB，JDK 21 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
docker run --rm --network csl-cache-aside_default -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$JDK_IMAGE" \
  java -cp "/cache/m2/$(basename "$DRIVER")" src/CacheAside.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
MCP=""
for c in io.micrometer:micrometer-core:1.17.1 io.micrometer:micrometer-commons:1.17.1 io.micrometer:micrometer-observation:1.17.1 \
         org.hdrhistogram:HdrHistogram:2.2.2 org.latencyutils:LatencyUtils:2.0.3; do
  MCP="$MCP:/cache/m2/$(basename "$(maven_jar "$c")")"
done
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$JDK_IMAGE" java -cp "${MCP#:}" src/GaugeCheck.java 2>/dev/null >"$OUT/gauge.tsv"
write_environment "$OUT/environment.txt" "redis: 8.10.1、mysql: 8.4.11（compose.yaml 固定 digest）" "jdbc: $MYSQL_JDBC_JAR_COORD" "micrometer: 1.17.1" "jdk_image: $JDK_IMAGE"
f="$OUT/output.tsv"
cat "$f" >&2
expect_regex "$f" "^cache_aside	.*库里 200，缓存里 100（TTL [0-9]+ 秒）" "Cache Aside：回填旧值，缓存 100、库 200，直到 TTL 到期"
expect_regex "$f" "^delete_inside_tx	.*库里 200，缓存里 100" "事务内删缓存：提交后缓存仍是旧值"
expect_regex "$f" "^double_delete\.fast	.*库里 200，缓存里 null" "延迟双删：回填早于第二次删除时，旧值被清掉"
expect_regex "$f" "^double_delete\.slow	.*库里 200，缓存里 100" "延迟双删：回填晚于第二次删除时，旧值留在缓存里"
expect_regex "$f" "^write_marker	.*跳过回填 1 次：库里 200，缓存里 null" "写入标记：跳过回填，缓存为空"
expect_regex "$f" "^natural	读写同时开始、不加控制，2,000 轮中结束后缓存仍是旧值的轮数：[0-9]+$" "自然并发的统计"
expect_line "$OUT/gauge.tsv" "naive	每次调用 gauge(name, 装箱值)：第二次传入 0.2 后读数 0.1；GC 之后读数 NaN" "同名 gauge 第二次注册被忽略，装箱值被回收后为 NaN"
expect_line "$OUT/gauge.tsv" "field	注册一次、更新字段：更新为 0.2 并 GC 之后读数 0.2" "注册一次、更新字段的写法"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
