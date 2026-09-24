#!/usr/bin/env bash
# 幂等键：并发同键、重放、请求不一致、可重试与不可重试失败、租约过期接管、业务与幂等记录分开提交时的崩溃窗口
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 21 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-idempotency-key
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
mysql_exec "$PROJECT" labs <schema/01-schema.sql
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
log "运行 src/Idempotency.java"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/Idempotency.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
{ echo "## idempotency_keys"; echo "SELECT idem_key, status, response, token FROM idempotency_keys ORDER BY idem_key;" | mysql_exec "$PROJECT" --default-character-set=utf8mb4 labs -t
  echo "## registrations"; echo "SELECT idem_key, COUNT(*) AS n, GROUP_CONCAT(executor ORDER BY id) AS executors FROM registrations GROUP BY idem_key ORDER BY idem_key;" | mysql_exec "$PROJECT" --default-character-set=utf8mb4 labs -t
} >"$OUT/final-tables.txt"
{ echo "image: $(docker inspect -f '{{.Config.Image}}' "$CID")"; echo "jdbc: $MYSQL_JDBC_JAR_COORD"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest）" "jdbc: $MYSQL_JDBC_JAR_COORD"

expect_line "$OUT/output.tsv" "concurrent.same_key	20 个并发请求：{EXECUTED=1, IN_PROGRESS=19}；报名写入 1 条" "20 个并发同键请求只执行一次"
expect_line "$OUT/output.tsv" "replay	完成后再发 5 次：{REPLAYED=5}，返回「201 registration for alice」；报名仍为 1 条" "完成后重放相同响应"
expect_line "$OUT/output.tsv" "mismatch	同一个键、不同的请求内容：MISMATCH「422 同一个幂等键对应了不同的请求」" "同键不同请求被拒绝"
expect_line "$OUT/output.tsv" "failure.retryable	第一次 FAILED（下游超时，已释放幂等键）；重试 EXECUTED；报名 1 条" "可重试失败释放键，重试后执行"
expect_line "$OUT/output.tsv" "failure.permanent	第一次 FAILED（400 同行人数不合法）；同一个键再发 REPLAYED（400 同行人数不合法）；报名 0 条" "不可重试失败被记录并重放"
expect_line "$OUT/output.tsv" "lease.SEPARATE	A（慢）→ EXECUTED，B（接管）→ EXECUTED；报名 2 条" "分开提交：租约过期后两个执行者都写入"
expect_line "$OUT/output.tsv" "lease.SAME_TRANSACTION	A（慢）→ FENCED，B（接管）→ EXECUTED；报名 1 条" "同一事务并校验 token：旧执行者被拒绝"
expect_line "$OUT/output.tsv" "crash.SEPARATE	第一次 崩溃；租约过期后重试 EXECUTED；报名 2 条" "分开提交：崩溃窗口导致重复执行"
expect_line "$OUT/output.tsv" "crash.SAME_TRANSACTION	第一次 崩溃；租约过期后重试 EXECUTED；报名 1 条" "同一事务：崩溃时一起回滚，重试只执行一次"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
