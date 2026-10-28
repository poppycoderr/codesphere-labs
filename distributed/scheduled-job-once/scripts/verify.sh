#!/usr/bin/env bash
# 多实例定时任务只跑一次：都执行、租约抢占、停顿后被接管时的令牌与运行键、cancel(true) 停不下来的任务
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：MySQL 容器 2 CPU、1 GB，JDK 25 客户端容器；约 1 分钟（不含拉取镜像）
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
docker run --rm --network csl-job-once_default -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$IMG" \
  java -cp "/cache/m2/$(basename "$DRIVER")" src/JobOnce.java 2>&1 | grep -v '^WARN\|^Loading class' >"$f"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest）" "jdbc: $MYSQL_JDBC_JAR_COORD" "jdk_image: $IMG"
cat "$f" >&2
# 两个实例的交错与停顿由代码固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
naive.both	两个实例都执行：结算表 1 行（主键挡住了重复），通知发了 2 次
lease.compete	同时抢 1 秒的租约：A 得到令牌 1，B 没抢到
takeover.plain	不设防：A 令牌 1 停顿后过期，B 接管得到令牌 2；B 写入，A 恢复后 写入；最终 total=150，由 A（令牌 1）写入
takeover.fenced	带令牌与运行键：A 令牌 1 停顿后过期，B 接管得到令牌 2；B 写入，A 恢复后 拒绝（令牌 1 已过期，当前 2）；最终 total=200，由 B（令牌 2）写入
runkey	租约过期后 A 自己重新抢到（令牌 3），对已完成的 d1 再写一次：拒绝（运行键 d1 已存在）；最终 total=200，由 B（令牌 2）写入
cancel	cancel(true) 返回 true/true，isCancelled=true；之后忙循环任务写入 1 行，阻塞在 SQL 里的任务写入 1 行
EXPECTED
log "全部通过，输出在 $OUT"
