#!/usr/bin/env bash
# 用数据库表当队列：自增 ID 与插入时间的分配顺序不是提交顺序、按上次最大值拉取的遗漏、SKIP LOCKED 认领与租约（MySQL 8.4.11）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：MySQL 8.4.11 容器（固定 digest，不暴露端口）与 python:3.14.8-slim 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
PROJECT=cs-dbqueue
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
f="$OUT/output.tsv"
docker run --rm --network "${PROJECT}_default" -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
{ "${C[@]}" config --images | sed "s/^/image: /"; echo "client_image: $PY"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "python_image: $PY" "pymysql: 1.2.3"
cat "$f" >&2
# 会话之间的交错由代码顺序固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	mysql=8.4.11 innodb_autoinc_lock_mode=2 transaction_isolation=REPEATABLE-READ
id.first_poll	A 拿到 id=1 未提交，B 拿到 id=2 已提交；消费者第一次拉取看到 ['B-晚分配早提交']，记下最大 id=2
id.second_poll	A 提交之后，消费者按 id > 2 拉取看到 []；表里实际有 ['A-早分配晚提交', 'B-晚分配早提交']，id=1 永远不会被拉到
id.gap	一个事务拿到 id=3 后回滚，下一条拿到 id=4：自增值不回收，id 有空洞，「连续」不能用来判断是否漏了
ts.poll	按 created_at 拉取：第一次看到 ['B-晚插入早提交']，A 提交后按 created_at > 上次最大值 看到 []（created_at 在插入时取值，不是提交时）
claim.skip_locked	两个消费者同时认领 2 条：w1 得到 [1, 2]，w2 跳过被锁的行得到 [3, 4]；另一个事务插入后未提交的 id=5，两者都没有认领到
claim.late	晚提交的任务提交之后，下一次按状态认领得到 [5]：认领看的是状态，不是「上次读到哪」
lease.reclaim	w1 认领后崩溃、租约过期：w4 重新认领到 [1, 2]；w2 的租约未过期，不会被抢走
lease.stale	w1 恢复后想把 id=1 标记完成（带 locked_by 条件）：影响 0 行；w4 标记完成：影响 1 行
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 ${OUT}（容器仍在运行，make clean 删除）"
