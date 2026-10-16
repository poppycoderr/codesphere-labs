#!/usr/bin/env bash
# 组织结构的知识层与实例层：按知识层校验挂接、只改数据新增规则、收紧规则后的存量、两条结构线、有效期与按日期查询、并发加边成环（PostgreSQL 18）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：一个 PostgreSQL 容器（不暴露端口）和 JDK 25 客户端容器；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
PROJECT=csl-org
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
JAR="/cache/m2/$(basename "$(maven_jar org.postgresql:postgresql:42.7.14)")"
f="$OUT/output.tsv"
docker run --rm --network "${PROJECT}_default" -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -cp "$JAR" src/OrgLab.java 2>/dev/null >"$f"
{ "${C[@]}" config --images | sed 's/^/image: /'; echo "jdk_image: $J25"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "postgresql: 18（compose.yaml 固定 digest）" "jdbc: postgresql 42.7.14"
cat "$f" >&2
# 输入固定，两个事务的交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 postgres=18.6
knowledge.allowed	TEAM-X 挂在 DEPT-A 下（行政线）：成功
knowledge.rejected	DEPT-B 挂在 TEAM-X 下（行政线）：被拒绝（IllegalStateException：知识层不允许 DEPT-B 挂在 TEAM-X 下（LINE））
knowledge.new_rule_before	TEAM-Y 直接挂在 DIV-R 下：被拒绝（IllegalStateException：知识层不允许 TEAM-Y 挂在 DIV-R 下（LINE））
knowledge.new_rule_after	在知识层加一行 (TEAM, DIVISION, LINE)，不改代码，再挂一次：成功
knowledge.tightened	随后删掉这条规则：新的挂接被拒绝，但已经存在、按当前规则不合法的关系有 1 条（TEAM-Y → DIV-R）：改知识层是一次数据迁移
structures.two_lines	TEAM-X 在职能线上挂到 DEPT-B：成功；TEAM-X 的上级：FUNCTION=DEPT-B, LINE=DEPT-A
validity.overlap	不结束旧关系，直接新增 TEAM-X → DEPT-C（7 月 1 日起）：被拒绝（PSQLException：conflicting key value violates exclusion constraint "org_link_child_structure_valid_excl"）
validity.move	先把旧关系截止到 7 月 1 日再新增：成功
validity.as_of	TEAM-X 在行政线上的上级：6 月 15 日 DEPT-A，8 月 1 日 DEPT-C
cycle.sequential	顺序执行：D1 挂到 D2 下 成功；D2 再挂到 D1 下 被拒绝（IllegalStateException：会形成环：D1 已经在 D2 之下）
cycle.read_committed	两个事务并发加 D1 → D2 与 D2 → D1，各自先查再插（READ COMMITTED）：提交成功 2 个；行政线上处在环里的组织 2 个
cycle.serializable	同样的两个事务用 SERIALIZABLE：提交成功 1 个，另一个失败（PSQLException：could not serialize access due to read/write dependencies among transactions）；处在环里的组织 0 个
cycle.lock_structure	检查前先 SELECT … FOR UPDATE 锁住结构线 LINE（READ COMMITTED）：提交成功 1 个，另一个失败（IllegalStateException：会形成环）；处在环里的组织 0 个
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
