#!/usr/bin/env bash
# Seata AT 模式：分支先本地提交与可见性、前镜像回滚、绕过 Seata 的写入导致回滚失败并持有全局锁、全局锁冲突、全局事务超时后被回滚
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：MySQL 8.4.11 与 Seata Server 2.6.0 容器（固定 digest，不暴露端口），JDK 25 客户端容器；客户端依赖由 mvn dependency:copy-dependencies 解析；约 2 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker mvn
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
[ -d target/lib ] || mvn -B -q dependency:copy-dependencies -DoutputDirectory=target/lib >/dev/null
C=(docker compose -f compose.yaml)
trap 'rc=$?; "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; exit $rc' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
sleep 20                                                    # Seata Server 没有健康检查端点，固定等待它启动
CP=$(ls target/lib/*.jar | sed "s#^target/lib#/w/target/lib#" | tr "\n" ":")
f="$OUT/output.tsv"
docker run --rm --network csl-seata-at_default -v "$PWD:/w" -w /w/config "$IMG" java -cp "/w/config:${CP%:}" /w/src/SeataAt.java 2>/dev/null | grep -E $'^[a-z.]+\t' >"$f" || true
ls target/lib >"$OUT/client-jars.txt"
write_environment "$OUT/environment.txt" "$( "${C[@]}" config --images | sed 's/^/image: /' | tr '\n' ' ')" "jdk_image: $IMG"
cat "$f" >&2
# 场景串行执行，交错由代码固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
commit.visible	全局事务提交前，不经 Seata 的连接读到账户 1 的余额 70（初始 100，分支扣了 30），undo_log 1 行
commit.done	全局提交后：状态 Finished，余额 70，undo_log 0 行
rollback	两个分支提交后余额 70/130；全局回滚：状态 Finished，余额恢复为 100/100
dirty.rollback	分支提交后另一个连接把余额 +5，再全局回滚：状态 RollbackFailed，异常 无，余额 75（不是初始的 100），undo_log 仍有 1 行
dirty.lock	回滚失败之后，新的全局事务经 Seata 改账户 1：失败（LockWaitTimeoutException）（失败的全局事务仍持有这一行的全局锁）
global.lock	全局事务 1 改了账户 2 尚未结束时，全局事务 2 改同一行：失败（LockConflictException）；事务 1 提交后余额 90
global.lock.raw	同样的情况下，不经 Seata 的连接直接改同一行：成功（全局锁只约束经过 Seata 的写入）
timeout	超时 2 秒的全局事务，分支提交后（余额 70）睡 5 秒：提交前协调器上的状态 Finished；调用 commit() 的异常 无，之后状态 Finished，余额 100
EXPECTED
log "全部通过，输出在 $OUT"
