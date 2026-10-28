#!/usr/bin/env bash
# 两阶段提交与 TCC：MySQL 8.4.11 的 XA 分支在协调者消失与实例重启后的状态、持有的锁与恢复，TCC 的空回滚、悬挂与重复确认
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：一个 MySQL 容器（不暴露端口，中途重启一次）与 JDK 25 客户端容器；约 2 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
PROJECT=csl-xa-tcc
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
JAR="/cache/m2/$(basename "$(maven_jar com.mysql:mysql-connector-j:9.7.0)")"
run() { docker run --rm --network "${PROJECT}_default" -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -cp "$JAR" src/XaLab.java "$1" 2>/dev/null; }
f="$OUT/output.tsv"
{
  run phase1
  "${C[@]}" restart mysql >/dev/null 2>&1
  "${C[@]}" up -d --wait >/dev/null 2>&1
  run phase2
  run tcc
} >"$f"
{ "${C[@]}" config --images | sed 's/^/image: /'; echo "jdk_image: $J25"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11" "jdbc: mysql-connector-j 9.7.0"
cat "$f" >&2
# 步骤顺序固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 mysql=8.4.11
xa.normal.prepared	两个分支都 PREPARE 之后，XA RECOVER 列出 [t1a, t1b]
xa.normal.committed	两个分支都 COMMIT：a=900 b=1100
xa.orphan.recover	准备之后协调者的连接全部断开：XA RECOVER 仍列出 [t2a, t2b]；余额仍是 a=900 b=1100
xa.orphan.lock	另一个事务更新 bank_a 的同一行（锁等待超时 2 秒）：失败（Lock wait timeout exceeded; try restarting transaction）
xa.orphan.other_row	更新同一张表的另一行：成功
xa.partial	协调者提交了分支 a 后消失：bank_a.id=2 已是 700，XA RECOVER 列出 [t2a, t2b, t3b]；只看数据库无法知道 t3 该提交还是回滚，需要协调者自己记录的决定
restart.recover	MySQL 实例重启之后 XA RECOVER：[t2a, t2b, t3b]
restart.lock	重启之后，另一个事务更新 bank_a.id=1：失败（Lock wait timeout exceeded; try restarting transaction）
restart.resolve	由恢复程序按协调者的决定从新会话 XA COMMIT 剩余分支：XA RECOVER []；id=1 a=700 b=1300；id=2 a=700 b=1300
tcc.1_naive.empty_cancel	Try 请求丢失、协调者超时后发来 Cancel：balance=1100 frozen=-100
tcc.1_naive.hanging	Cancel 之后迟到的 Try 才到达：balance=1000 frozen=0（之后不会再有 Confirm 或 Cancel）
tcc.1_naive.double_confirm	Try 后 Confirm 被重试两次：balance=900 frozen=-100
tcc.2_empty_only.empty_cancel	Try 请求丢失、协调者超时后发来 Cancel：balance=1000 frozen=0
tcc.2_empty_only.hanging	Cancel 之后迟到的 Try 才到达：balance=900 frozen=100（之后不会再有 Confirm 或 Cancel）
tcc.2_empty_only.double_confirm	Try 后 Confirm 被重试两次：balance=900 frozen=0
tcc.3_guarded.empty_cancel	Try 请求丢失、协调者超时后发来 Cancel：balance=1000 frozen=0
tcc.3_guarded.hanging	Cancel 之后迟到的 Try 才到达：balance=1000 frozen=0（之后不会再有 Confirm 或 Cancel）
tcc.3_guarded.double_confirm	Try 后 Confirm 被重试两次：balance=900 frozen=0
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 ${OUT}（容器仍在运行，make clean 删除）"
