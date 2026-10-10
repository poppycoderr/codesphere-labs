#!/usr/bin/env bash
# 测试数据的归属：固定数据相撞、数整张表的断言、按模式清理删掉别人的数据、上次运行的残留、事务回滚隔离的边界、每个进程一个库
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 25 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
JDK_IMAGE="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
JDBC="com.mysql:mysql-connector-j:9.7.0"
PROJECT=csl-test-data-ownership
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
DRIVER=$(maven_jar "$JDBC")
f="$OUT/output.tsv"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/TestDataLab.java 2>/dev/null >"$f"
{ echo "image: $(docker inspect -f '{{.Config.Image}}' "$CID")"; echo "jdbc: $JDBC"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest）" "jdbc: $JDBC"
cat "$f" >&2
# 两个「测试」的步骤按固定顺序交替执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 mysql=8.4.11
fixed.same_row	两个测试的准备步骤都插入 test@example.com：A 成功，B 失败（SQLIntegrityConstraintViolationException）
count.whole_table	A 下了一单后断言「NEW 状态的订单有 1 条」，此时 B 也下了一单：实际 2 条，A 失败
count.scoped	断言改成只数自己那个用户的订单：实际 1 条，A 通过
cleanup.pattern	B 结束时清理 DELETE … WHERE email LIKE 'test%'：删了 2 行；A 随后查自己刚插入的用户：0 行，A 失败
cleanup.owned	每条数据带上创建它的那次运行的标识，清理只删自己的：删了 1 行；A 查自己的用户：1 行，A 通过
leftover.fixed	上一次运行没走到清理就退出了；这一次的准备步骤再插入 test@example.com：失败（SQLIntegrityConstraintViolationException）
leftover.unique	邮箱里带上本次运行的标识（u-7f3a@example.com）：成功；上次留下的那行仍在表里 = true
rollback.visibility	测试在一个不提交的事务里准备数据：同一条连接看到 1 行；被测代码如果用另一条连接（异步线程、新开的事务、另一个进程）看到 0 行
rollback.blocking	这个事务还没结束时，另一个测试插入同一个邮箱：失败（MySQLTransactionRollbackException）（等了 1 秒的锁）
rollback.auto_increment	回滚之后再插入一行，它的自增 ID = 3（表里只有这 1 行）
per_worker_db	两个并行的测试进程各用一个库，都用固定的 test@example.com：插入 成功 / 成功；进程 1 数整张表的断言得到 1；进程 2 按模式清理删了 1 行，进程 1 的用户还在 = true
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
