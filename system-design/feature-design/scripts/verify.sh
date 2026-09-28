#!/usr/bin/env bash
# 典型业务功能：ZSET 同分排序与分数编码、百万成员排行榜的内存与取榜吞吐、GEOSEARCH、二倍均值法拆红包
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 Redis 容器（2 CPU、1 GB）与 JDK 21 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
java_in_network "$("${C[@]}" ps -q redis)" src/Features.java >"$OUT/output.tsv"
log "redis-benchmark：取前 10 名、按名次深翻页、按分数带 LIMIT 偏移深翻页（容器内，50 个连接，各 10 万次）"
bench() {
  local n="$1"; shift
  "${C[@]}" exec -T redis redis-benchmark -n "$n" -c 50 -q "$@" 2>/dev/null | tr '\r' '\n' | grep -E "requests per second" | tail -1
}
{
  echo "top10	$(bench 100000 ZREVRANGE rank:big 0 9 WITHSCORES)"
  echo "page500k	$(bench 100000 ZREVRANGE rank:big 500000 500009 WITHSCORES)"
  echo "myrank	$(bench 100000 ZREVRANK rank:big user:123456)"
  echo "byscore500k	$(bench 100000 ZREVRANGEBYSCORE rank:big +inf -inf WITHSCORES LIMIT 500000 10)"
} >"$OUT/benchmark.tsv"
log "SLOWLOG：记录每条命令的执行耗时（微秒）"
"${C[@]}" exec -T redis sh -c 'redis-cli CONFIG SET slowlog-log-slower-than 0 >/dev/null; redis-cli SLOWLOG RESET >/dev/null
  redis-cli ZREVRANGE rank:big 0 9 >/dev/null; redis-cli ZREVRANGE rank:big 500000 500009 >/dev/null
  redis-cli ZREVRANGEBYSCORE rank:big +inf -inf LIMIT 500000 10 >/dev/null; redis-cli SLOWLOG GET 3' | tr -d '\r' >"$OUT/slowlog.txt"
"${C[@]}" exec -T redis redis-cli INFO server | tr -d '\r' | grep -E '^redis_version:' >"$OUT/server.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）" "jdk_image: $JDK_IMAGE"
f="$OUT/output.tsv"
cat "$f" "$OUT/benchmark.tsv" >&2
expect_line "$f" "ZADD 100 userA、100 userB、90 userC → ZREVRANGE：userB 100, userA 100, userC 90" "同分时 ZREVRANGE 按成员名逆字典序"
expect_regex "$f" "^tie\.packed	.*ZREVRANGE：\[userA, userB, userC\]；userA 的 score / 10\^11 取整 = 100$" "分数编码后先提交的在前，可还原得分"
expect_regex "$f" "^rank\.big	100 万成员：ZCARD=1000000，OBJECT ENCODING=skiplist，" "百万成员 ZSET 为 skiplist 编码"
expect_line "$f" "GEOSEARCH FROMLONLAT 116.400 39.910 BYRADIUS 1 km ASC WITHDIST → 店A 0.2790, 店B 0.7009" "1 公里内两家店，按距离排序"
expect_line "$f" "GEOSEARCH FROMLONLAT 116.400 39.910 BYRADIUS 10 km ASC WITHDIST → 店A 0.2790, 店B 0.7009, 店C 8.6047" "10 公里内三家店"
expect_line "$f" "TYPE shops = zset" "GEO 底层是 ZSET"
expect_regex "$f" "^redpacket	.*金额不等于总额的轮次 0；最小 0\.01 元" "拆红包总额守恒、每人至少 1 分"
python3 - "$f" "$OUT/benchmark.tsv" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
avg = [float(x) for x in re.search(r"各位置平均：(.*)$", t, re.M)[1].split()]
assert all(abs(a - 10) < 0.2 for a in avg), avg
b = dict(l.split("\t", 1) for l in open(sys.argv[2], encoding="utf-8").read().splitlines())
qps = {k: float(re.search(r"([\d.]+) requests per second", v)[1]) for k, v in b.items()}
assert qps["page500k"] > qps["top10"] / 2, qps                  # 按名次翻页：跳表按跨度定位，和取前 10 名同一量级
assert qps["byscore500k"] > qps["top10"] / 2, qps              # 按分数带 LIMIT 偏移：这个版本同样没有明显变慢
print(f"通过：各位置平均 {min(avg)}—{max(avg)} 元；取前 10 名 {qps['top10']:,.0f} 次/秒，按名次翻到 50 万名 {qps['page500k']:,.0f} 次/秒，按分数 LIMIT 500000 {qps['byscore500k']:,.0f} 次/秒")
PY
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
