#!/usr/bin/env bash
# 活动名额 100、200 个并发请求：先数再插 / 聚合根 + 版本号 / FOR UPDATE / 条件更新，每种写法 3 轮
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
CID=$(mysql_up csl-aggregate)
log "运行 3 轮（约 10 秒）"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/Oversell.java 2>&1 | grep -v '^WARN' >"$OUT/oversell.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，2 CPU、1 GB）" "jdk_in_container: $(docker run --rm "$JDK_IMAGE" java -version 2>&1 | head -1)" "mysql_connector_java: 8.0.27"
python3 - "$OUT/oversell.txt" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
a = [int(x) for x in re.findall(r"A 查询后插入：成功 (\d+)", t)]
b = re.findall(r"B 版本号：成功 (\d+)，满员 (\d+)，放弃 (\d+)，冲突 (\d+) 次", t)
c = [int(x) for x in re.findall(r"C FOR UPDATE：成功 (\d+)", t)]
d = [int(x) for x in re.findall(r"D 条件更新：成功 (\d+)", t)]
assert len(a) == len(b) == len(c) == len(d) == 3, "应有 3 轮结果"
assert all(x > 100 for x in a), f"先数再插应当超卖：{a}"
assert all(int(ok) <= 100 and int(ok) + int(full) + int(gave) == 200 for ok, full, gave, _ in b), f"版本号写法不应超卖：{b}"
assert all(int(gave) > 0 for _, _, gave, _ in b), f"热点上乐观锁应出现放弃：{b}"
assert c == [100] * 3 and d == [100] * 3, f"FOR UPDATE 与条件更新应正好 100：{c} {d}"
print(f"通过：先数再插超卖 {a}；版本号成功 {[int(x[0]) for x in b]}、放弃 {[int(x[2]) for x in b]}；FOR UPDATE {c}；条件更新 {d}")
PY
