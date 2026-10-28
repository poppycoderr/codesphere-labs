#!/usr/bin/env bash
# 时间区间不重叠：唯一键、先查后插、锁成员行（MySQL 8.4.11）与排他约束（PostgreSQL 18）在两个并发事务下的结果
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：一个 MySQL 与一个 PostgreSQL 容器（不暴露端口）和 JDK 25 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
PROJECT=csl-interval-overlap
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
JARS=""
for coord in com.mysql:mysql-connector-j:9.7.0 org.postgresql:postgresql:42.7.14; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm --network "${PROJECT}_default" -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -cp "${JARS#:}" src/OverlapLab.java 2>/dev/null >"$f"
{ "${C[@]}" config --images | sed 's/^/image: /'; echo "jdk_image: $J25"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11" "postgresql: 18（compose.yaml 固定 digest）" "jdbc: mysql-connector-j 9.7.0, postgresql 42.7.14"
cat "$f" >&2
# 两个事务的交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 mysql=8.4.11 postgres=18.6
unique_key	唯一键 (member_id, starts)，已有 09:00—12:00，再插入 10:00—11:00：插入成功，现在有 2 条重叠的值班
check_then_insert	两个事务各自先查「有没有重叠」再插入（REPEATABLE READ）：提交成功 2 个；表里重叠的值班 2
lock_member	检查前先 SELECT … FOR UPDATE 锁住成员行（后到的事务在这里等待先到的提交）：提交成功 1 个，另一个失败（IllegalStateException：重叠）；表里的值班 1
pg_exclude	PostgreSQL 排他约束 EXCLUDE (member_id WITH =, period WITH &&)，两个事务并发插入重叠区间：提交成功 1 个，另一个失败（PSQLException：conflicting key value violates exclusion constraint "duty_member_id_period_excl"）；表里的值班 1
pg_boundaries	另一天：半开区间 [09:00, 12:00) 之后接 [12:00, 15:00)：插入成功；闭区间 [15:00, 18:00] 之后接 [18:00, 20:00)：被拒绝（18:00 同时属于两段）
pg_other_member	另一个成员同一时段：插入成功
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 ${OUT}（容器仍在运行，make clean 删除）"
