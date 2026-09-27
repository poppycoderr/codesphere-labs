#!/usr/bin/env bash
# 锁与事务：锁在事务内外释放、唯一键兜底、先查后改与条件更新、三种主键的插入代价
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 21 容器；约 2 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-lock-and-transaction
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
mysql_exec "$PROJECT" labs <schema/01-schema.sql
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
log "运行 src/LockAndTransaction.java"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/LockAndTransaction.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
echo "SELECT @@version, @@transaction_isolation, @@innodb_buffer_pool_size;" | mysql_exec "$PROJECT" labs -t >"$OUT/mysql-settings.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest）" "jdbc: $MYSQL_JDBC_JAR_COORD"
f="$OUT/output.tsv"
cat "$f" >&2
expect_regex "$f" "^lock\.LOCK_INSIDE_TX	.*出现重复订单的用户 [1-9][0-9]* 个" "锁在事务内释放：出现重复订单"
expect_regex "$f" "^lock\.LOCK_AROUND_TX	.*订单 200 条，有订单的用户 200 个，出现重复订单的用户 0 个" "锁包住事务：每个用户一条订单"
expect_regex "$f" "^lock\.LOCK_INSIDE_TX\.unique	.*订单 200 条.*出现重复订单的用户 0 个，唯一键拒绝 [1-9][0-9]* 次" "锁放错位置时，唯一键兜住了重复插入"
expect_regex "$f" "^transition\.check_then_act	.*同时有支付时间和关闭时间的订单 [1-9][0-9]* 笔" "先查后改：两条路径都改写了同一笔订单"
expect_regex "$f" "^transition\.conditional	1000 笔订单：支付路径成功 ([0-9]+) 次，关闭路径成功 ([0-9]+) 次，同时有支付时间和关闭时间的订单 0 笔" "条件更新：没有订单被两条路径同时改写"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"^transition\.conditional\t.*支付路径成功 (\d+) 次，关闭路径成功 (\d+) 次", t, re.M)
assert int(m[1]) + int(m[2]) == 1000, m.groups()
pk = {k: (int(ms), float(tot)) for k, ms, tot in re.findall(r"^pk\.(\S+)\t.*?(\d+) ms（.*合计 ([\d.]+) MB", t, re.M)}
b, o, r = pk["BIGINT"], pk["BINARY(16)"], pk["CHAR(36)"]
assert b[1] < o[1] < r[1], pk
assert r[0] > b[0], pk
print(f"通过：条件更新两条路径成功数之和为 1000；空间 BIGINT {b[1]} < 有序 UUID {o[1]} < 随机 UUID {r[1]} MB；随机 UUID 插入 {r[0]} ms，慢于 BIGINT {b[0]} ms")
PY
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
