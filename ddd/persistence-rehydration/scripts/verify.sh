#!/usr/bin/env bash
# 持久化与重建：保存—重建往返、重建不是创建、版本号冲突、子表的两种保存方式、票种继承的三种映射
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 21 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-ddd-persistence
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
mysql_exec "$PROJECT" labs <schema/01-schema.sql
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
log "运行 src/Persistence.java"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/Persistence.java "$OUT" 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
{ echo "image: $(docker inspect -f '{{.Config.Image}}' "$CID")"; echo "jdbc: $MYSQL_JDBC_JAR_COORD"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，2 CPU、1 GB）" "jdbc: $MYSQL_JDBC_JAR_COORD"

expect_line "$OUT/output.tsv" "一致=true；重建后待发布事件 0 个（保存前 7 个）" "保存—重建往返一致，重建不产生事件"
expect_line "$OUT/output.tsv" "历史场次（容量 5）经 restore 重建成功，待发布事件 0 个；经 open 创建抛出「容量至少为 10」；放宽规则后按 register 重放，登记事件 7 个" "重建不是创建"
expect_line "$OUT/output.tsv" "A 写入 2 行并提交；B ConcurrentModification，回滚；最终版本 1，候补 [a6, a7, x]" "版本号冲突：后提交者回滚，子表一起回滚"
expect_line "$OUT/output.tsv" "children.diff	已有 1000 个参会人的场次新增 1 人：写入 2 行" "按差异保存只写 2 行"
expect_line "$OUT/output.tsv" "children.replace-all	已有 1000 个参会人的场次新增 1 人：写入 2004 行" "整体替换写 2004 行"
for m in single-table concrete-tables joined; do
  expect_regex "$OUT/output.tsv" "^inherit\.$m	场次 42 的全部票种 300 行，平均 [0-9.]+ms；价格高于 450 元的付费票 3018 行" "$m：两个查询的结果行数一致"
done
expect_line "$OUT/inheritance-plans.txt" "-> Append" "每个具体类一张表：多态查询是 UNION ALL"
expect_line "$OUT/inheritance-plans.txt" "-> Nested loop left join" "父表 + 子表：多态查询需要左连接每个子表"
expect_line "$OUT/output.tsv" "插入没有价格的付费票：失败（Check constraint 'chk_subtype' is violated.）" "单表：CHECK 约束拒绝缺列的子类型"
expect_line "$OUT/output.tsv" "在免费票表和付费票表各插入 id=900002：成功 / 成功" "每个具体类一张表：跨表的 id 唯一性没有约束"
expect_line "$OUT/output.tsv" "只插入父表 type='PAID' 而不插入价格子表：成功" "父表 + 子表：数据库不保证子表行存在"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
