#!/usr/bin/env bash
# 库存先在 Redis 预扣、再到 MySQL 扣减：朴素写法的三个漏洞；预占记录 + 数据库为准 + 对账的修正写法，逐个崩溃点注入后对账，以及 50 个订单并发抢 10 件
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Redis 与 MySQL 容器各 2 CPU、1 GB，JDK 25 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
C=(docker compose -f compose.yaml)
trap 'rc=$?; "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; exit $rc' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
f="$OUT/output.tsv"
docker run --rm --network csl-stock-reserve_default -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$IMG" \
  java -cp "/cache/m2/$(basename "$DRIVER")" src/StockReserve.java 2>&1 | grep -v '^WARN\|^Loading class' >"$f"
write_environment "$OUT/environment.txt" "redis: 8.10.1、mysql: 8.4.11（compose.yaml 固定 digest）" "jdbc: $MYSQL_JDBC_JAR_COORD" "jdk_image: $IMG"
cat "$f" >&2
# 场景串行执行、崩溃点与连接断开都是注入的，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
naive.db-false	数据库扣减 0 行、方法返回 false：Redis 9，数据库可用 10、已售 0，遗留预占 0，两边一致=False
naive.commit-fail	方法返回 true，提交失败：Redis 9，数据库可用 10、已售 0，遗留预占 0，两边一致=False
naive.cancel-retry	取消时数据库第一次提交失败、重试成功：Redis 11，数据库可用 10、已售 0，遗留预占 0，两边一致=False
fixed.db-false	数据库拒绝：数据库拒绝，已释放预占；Redis 10，数据库可用 10、已售 0，遗留预占 0，两边一致=True
fixed.commit-fail	提交时连接断开：提交结果未知，保留预占；对账前 Redis 9，数据库可用 10、已售 0，遗留预占 1，两边一致=False；对账处理 1 项后 Redis 10，数据库可用 10、已售 0，遗留预占 0，两边一致=True
fixed.cancel-twice	同一订单取消两次：Redis 10，数据库可用 10、已售 0，遗留预占 0，两边一致=True
crash.after-reserve	崩溃点 AFTER_RESERVE：崩溃后 Redis 9，数据库可用 10、已售 0，遗留预占 1，两边一致=False；对账处理 1 项后 Redis 10，数据库可用 10、已售 0，遗留预占 0，两边一致=True；再对账一次处理 0 项
crash.after-commit	崩溃点 AFTER_COMMIT：崩溃后 Redis 9，数据库可用 9、已售 1，遗留预占 1，两边一致=False；对账处理 1 项后 Redis 9，数据库可用 9、已售 1，遗留预占 0，两边一致=True；再对账一次处理 0 项
crash.cancel-after-db	崩溃点 CANCEL_AFTER_DB：崩溃后 Redis 9，数据库可用 10、已售 0，遗留预占 0，两边一致=False；对账处理 1 项后 Redis 10，数据库可用 10、已售 0，遗留预占 0，两边一致=True；再对账一次处理 0 项
fixed.concurrent	50 个订单并发抢 10 件：成功 10 个；Redis 0，数据库可用 0、已售 10，遗留预占 0，两边一致=True
EXPECTED
log "全部通过，输出在 $OUT"
