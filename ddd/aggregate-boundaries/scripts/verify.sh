#!/usr/bin/env bash
# 聚合边界：候补递补放在场次聚合内还是拆成独立聚合、大聚合与小聚合的锁等待、回滚时事件是否流出、只能经由根修改
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 21 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-ddd-aggregate
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
mysql_exec "$PROJECT" labs <schema/01-schema.sql
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
log "运行 src/Aggregates.java"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/Aggregates.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
{ echo "image: $(docker inspect -f '{{.Config.Image}}' "$CID")"; echo "jdbc: $MYSQL_JDBC_JAR_COORD"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，2 CPU、1 GB）" "jdbc: $MYSQL_JDBC_JAR_COORD"

python3 - "$OUT/output.tsv" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
def rows(key):
    return re.findall(rf"^{re.escape(key)}\.\d\t(.*)$", t, re.M)
comb, split = rows("promotion.combined"), rows("promotion.split")
assert len(comb) == len(split) == 3, "promotion 应有 3 轮"
for line in comb:
    assert "已确认 20/20，候补者递补 10，新报名直接确认 0" in line, line
late = [int(re.search(r"新报名直接确认 (\d+)", l).group(1)) for l in split]
assert all(re.search(r"已确认 (\d+)/20", l) and int(re.search(r"已确认 (\d+)/20", l).group(1)) <= 20 for l in split), split
assert sum(late) > 0, f"拆分写法应出现新报名插队：{split}"
print(f"通过：同一聚合 3 轮新报名插队 0；拆分写法新报名直接确认 {late}，名额都没有超过 20")
big = [tuple(int(x) for x in re.search(r"行锁等待 (\d+) 次，累计锁等待 (\d+)ms，总耗时 (\d+)ms", l).groups()) for l in rows("contention.event-root")]
small = [tuple(int(x) for x in re.search(r"行锁等待 (\d+) 次，累计锁等待 (\d+)ms，总耗时 (\d+)ms", l).groups()) for l in rows("contention.session-root")]
assert len(big) == len(small) == 3
assert all("确认 200" in l for l in rows("contention.event-root") + rows("contention.session-root"))
assert all(b[1] > s[1] and b[2] > s[2] for b, s in zip(big, small)), f"大聚合的锁等待与耗时应更多：{big} {small}"
print(f"通过：以活动为根 (等待次数, 累计等待ms, 总耗时ms)={big}；以场次为根={small}")
PY
expect_line "$OUT/output.tsv" "rollback.direct	保存失败（Duplicate entry '1-alice'）并回滚：确认数仍为 1，已发布事件 1 个" "领域方法里直接发布：回滚后事件已经流出"
expect_line "$OUT/output.tsv" "rollback.after-commit	保存失败（Duplicate entry '1-alice'）并回滚：确认数仍为 1，已发布事件 0 个" "提交后发布：回滚时没有事件"
expect_line "$OUT/output.tsv" "向只读视图添加：UnsupportedOperationException；根修改后视图可见 [alice, bob]，副本仍为 [alice]" "只读视图挡住外部修改，但会反映根的修改"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
