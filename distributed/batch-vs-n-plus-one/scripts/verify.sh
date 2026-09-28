#!/usr/bin/env bash
# 分两次查的写法：N+1、去重后逐条、一次 IN 批量、同库 JOIN；直连与约 1 ms 往返两种网络
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 21 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-batch-vs-n-plus-one
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
mysql_exec "$PROJECT" labs <schema/01-schema.sql
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
log "运行 src/BatchJoin.java"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/BatchJoin.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest）" "jdbc: $MYSQL_JDBC_JAR_COORD"
f="$OUT/output.tsv"
cat "$f" >&2
expect_regex "$f" "^direct\.n_plus_one	直连：1000 次查询" "N+1：1000 次查询"
expect_regex "$f" "^direct\.dedup_then_each	直连：200 次查询" "先去重：200 次查询"
expect_regex "$f" "^direct\.batch_in	直连：1 次查询" "批量 IN：1 次查询"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
m = {k: float(v) for k, v in re.findall(r"^(\S+)\t.*耗时 ([\d.]+) ms", t, re.M)}
for net in ("direct", "rtt1ms"):
    assert m[f"{net}.n_plus_one"] > 10 * m[f"{net}.batch_in"], m
    assert m[f"{net}.dedup_then_each"] < m[f"{net}.n_plus_one"], m
assert m["rtt1ms.n_plus_one"] > 3 * m["direct.n_plus_one"], m
assert m["rtt1ms.n_plus_one"] >= 1000, m                          # 1000 次往返，每次约 1 ms
print(f"通过：直连 N+1 {m['direct.n_plus_one']} ms、批量 {m['direct.batch_in']} ms；约 1 ms 往返时 N+1 {m['rtt1ms.n_plus_one']} ms、批量 {m['rtt1ms.batch_in']} ms")
PY
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
