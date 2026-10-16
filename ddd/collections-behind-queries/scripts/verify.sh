#!/usr/bin/env bash
# 领域模型里的集合背后的查询：size() 检查名额、计数器、往 bag 与 Set 里加一项、根版本号、N+1 与 join fetch、orphanRemoval 的 clear 与批量删除（Hibernate 7.4.13、PostgreSQL 18）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：一个 PostgreSQL 容器（不暴露端口）、Maven 3.10.0 容器（下载依赖）与 JDK 25 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
PROJECT=csl-collections
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
rm -rf build/lib; mkdir -p build/lib
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -q -B dependency:copy-dependencies -DoutputDirectory=/w/build/lib >/dev/null 2>&1 || fail "依赖下载失败"
ls build/lib | sort >"$OUT/classpath.txt"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC --network "${PROJECT}_default" -v "$PWD:/w" -w /w "$J25" sh -c 'java -Duser.timezone=UTC -cp "$(ls build/lib/*.jar | tr "\n" ":")" src/CollectionLab.java' 2>/dev/null >"$f"
{ "${C[@]}" config --images | sed "s/^/image: /"; echo "jdk_image: $J25"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "hibernate-core: 7.4.13.Final" "postgresql: 18（compose.yaml 固定 digest）"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 hibernate=7.4.13.Final
size_check	1000 人的场次，聚合方法里用 registrations.size() 检查名额后报名：SQL 3 条，加载实体 1001 个
counter_check	改用场次上的计数器检查名额：SQL 4 条，加载实体 1 个
bag_add	不检查名额，只往 List（bag）里加一项：SQL 2 条，加载实体 1 个
version	场次的 @Version：只往 mappedBy 的集合里加一项前后 1 → 1；同时修改计数器前后 1 → 2：只改子项时，根的版本号不变，乐观锁挡不住两个并发的报名
set_add	往 Set 映射的候补里加一项：SQL 3 条，加载实体 1001 个
count_query	用查询回答「报了多少人」：SQL 1 条，加载实体 0 个
n_plus_one	查出 50 个小场次，逐个读 registrations.size()：SQL 51 条，加载实体 200 个
join_fetch	同样的遍历，查询时 join fetch 集合：SQL 1 条，加载实体 200 个
clear_orphans	orphanRemoval 的集合 clear()（1005 项）：SQL 1007 条，加载实体 1006 个
after_clear	清空后报名表里这个场次剩下 0 条
bulk_delete	50 个小场次的报名用一条批量删除：SQL 1 条，加载实体 0 个
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
