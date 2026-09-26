#!/usr/bin/env bash
# 深分页与游标分页、按 (created_at, id) 的游标、二级索引数量对批量写入的影响
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 21 容器；约 2 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-pagination
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
log "运行 src/Pagination.java"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/Pagination.java "$OUT" 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
{ echo "image: $(docker inspect -f '{{.Config.Image}}' "$CID")"; echo "jdbc: $MYSQL_JDBC_JAR_COORD"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，2 CPU、1 GB）" "jdbc: $MYSQL_JDBC_JAR_COORD"

python3 - "$OUT/output.tsv" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
num = lambda s: int(s.replace(",", ""))
pages = {int(o): (num(a), float(b), num(c), float(d), s) for o, a, b, c, d, s in re.findall(
    r"^page\.(\d+)\t.*LIMIT offset 读取 ([\d,]+) 行、平均 ([\d.]+)ms；游标 WHERE id > \? 读取 ([\d,]+) 行、平均 ([\d.]+)ms；结果相同=(\w+)", t, re.M)}
assert sorted(pages) == [0, 10000, 100000, 400000], pages
assert all(v[4] == "true" for v in pages.values()), pages
assert pages[400000][0] > 400000 and pages[400000][2] < 100, pages[400000]
assert pages[400000][1] > 20 * pages[400000][3], pages[400000]
print("通过：offset=400000 时 LIMIT 读取 {:,} 行、{}ms，游标读取 {} 行、{}ms；各深度结果一致".format(pages[400000][0], pages[400000][1], pages[400000][2], pages[400000][3]))
tc = {f: tuple(map(num, g)) for f, *g in re.findall(r"^time_cursor\.(\w+)\t.*取到 ([\d,]+) 行、不重复 ([\d,]+) 行，总共读取 ([\d,]+) 行，第 50 页读取 ([\d,]+) 行", t, re.M)}
assert all(v[0] == v[1] == 1000 for v in tc.values()), tc
assert tc["row"][3] > 500 and tc["expanded"][3] < 50, tc
print(f"通过：行构造器游标第 50 页读取 {tc['row'][3]} 行，展开写法 {tc['expanded'][3]} 行；两种写法都取到 1000 行不重复")
w = {int(k): num(v) for k, v in re.findall(r"^write\.(\d)\t.*3 轮中位数 ([\d,]+)ms", t, re.M)}
assert w[0] < w[2] < w[5], w
print(f"通过：写入 20 万行，0/2/5 个二级索引中位数 {w[0]}/{w[2]}/{w[5]} ms")
PY
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
