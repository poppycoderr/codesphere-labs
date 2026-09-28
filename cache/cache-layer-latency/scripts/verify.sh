#!/usr/bin/env bash
# 缓存分层的访问延迟：Caffeine 本地缓存、Redis GET、MySQL 主键查询，同一个客户端容器、单连接顺序请求
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Redis 与 MySQL 容器各 2 CPU、1 GB，JDK 21 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
CAFFEINE=$(maven_jar com.github.ben-manes.caffeine:caffeine:3.3.0)
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
docker run --rm --network csl-cache-layers_default -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$JDK_IMAGE" \
  java -cp "/cache/m2/$(basename "$CAFFEINE"):/cache/m2/$(basename "$DRIVER")" src/Layers.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
write_environment "$OUT/environment.txt" "redis: 8.10.1、mysql: 8.4.11（compose.yaml 固定 digest，各 2 CPU、1 GB）" "caffeine: 3.3.0" "jdbc: $MYSQL_JDBC_JAR_COORD" "jdk_image: $JDK_IMAGE"
f="$OUT/output.tsv"
cat "$f" >&2
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
local = float(re.search(r"平均 ([\d.]+) ns/次", t)[1])
redis = float(re.search(r"^redis\t.*p50 ([\d.]+) ms", t, re.M)[1])
mysql = float(re.search(r"^mysql\t.*p50 ([\d.]+) ms", t, re.M)[1])
assert local < 1000, local                                        # 本地缓存在纳秒级
assert redis * 1e6 > 50 * local, (redis, local)                   # 本地缓存比 Redis 快一两个数量级以上
assert mysql > redis, (mysql, redis)                              # MySQL 主键查询慢于 Redis GET
assert mysql / redis < redis * 1e6 / local, (mysql, redis, local)  # Redis 与 MySQL 的差距，小于本地缓存与 Redis 的差距
print(f"通过：本地 {local} ns，Redis p50 {redis} ms，MySQL p50 {mysql} ms；Redis/本地 {redis * 1e6 / local:,.0f} 倍，MySQL/Redis {mysql / redis:.1f} 倍")
PY
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
