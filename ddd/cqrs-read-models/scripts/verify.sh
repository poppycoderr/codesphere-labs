#!/usr/bin/env bash
# CQRS：同一个运营看板的四种读法——经仓储加载聚合、GROUP BY、同步读模型、异步投影（暂停、追赶、重放）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 21 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-ddd-cqrs
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
mysql_exec "$PROJECT" labs <schema/01-schema.sql
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
log "运行 src/Cqrs.java"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/Cqrs.java "$OUT" 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
{ echo "image: $(docker inspect -f '{{.Config.Image}}' "$CID")"; echo "jdbc: $MYSQL_JDBC_JAR_COORD"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，2 CPU、1 GB）" "jdbc: $MYSQL_JDBC_JAR_COORD"

python3 - "$OUT/output.tsv" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
ms = {k: float(v) for k, v in re.findall(r"^read\.([\w-]+)\t返回 200 行，平均 ([0-9.]+)ms", t, re.M)}
assert set(ms) == {"aggregate", "group-by", "sync-read-model"}, ms
assert ms["aggregate"] > ms["group-by"] > ms["sync-read-model"], f"读法耗时应依次下降：{ms}"
print(f"通过：经聚合 {ms['aggregate']}ms > GROUP BY {ms['group-by']}ms > 同步读模型 {ms['sync-read-model']}ms")
PY
expect_line "$OUT/output.tsv" "每次读取 50200 行" "经仓储加载聚合：每次读取全部 50200 行"
expect_regex "$OUT/output.tsv" "三种读法结果一致：聚合=([0-9a-f]+)，GROUP BY=\\1，同步读模型=\\1" "三种读法的结果摘要相同"
expect_line "$OUT/query-plans.txt" "rows=50000 loops=1" "GROUP BY 在执行时扫描 5 万行报名"
expect_line "$OUT/output.tsv" "回填后同步读模型与 GROUP BY 一致=true" "同步读模型回填后与写侧一致"
expect_regex "$OUT/output.tsv" "^async\\.build\t.*与 GROUP BY 一致=true" "异步读模型从 0 重放后一致"
expect_line "$OUT/output.tsv" "投影器暂停期间执行 500 次报名：积压 500 个事件，异步读模型 200/200 个场次与写侧不一致；同步读模型一致=true" "暂停投影器：异步读模型陈旧，同步读模型不受影响"
expect_regex "$OUT/output.tsv" "^async\\.resumed\t恢复后追上 500 个事件：[0-9]+ms；与 GROUP BY 一致=true" "恢复后追上"
expect_regex "$OUT/output.tsv" "^async\\.replay\t人为改坏 10 个场次后清空重放 [0-9]+ 个事件：[0-9]+ms；与 GROUP BY 一致=true" "读模型可以从事件重放修复"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
