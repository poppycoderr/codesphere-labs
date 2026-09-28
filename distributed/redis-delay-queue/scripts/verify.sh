#!/usr/bin/env bash
# Redis ZSET 延时队列：轮询间隔与批量上限造成的触发延迟、两步取删的重复消费、取出后崩溃的丢失与租约回收
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 Redis 容器与 JDK 21 客户端容器；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null
java_in_network "$("${C[@]}" ps -q redis)" src/DelayQueue.java >"$OUT/output.tsv"
"${C[@]}" exec -T redis redis-cli INFO server | tr -d '\r' | grep -E '^redis_version:' >"$OUT/server.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）" "jdk_image: $JDK_IMAGE"
f="$OUT/output.tsv"
cat "$f" >&2
expect_regex "$f" "^dup\.twostep	.*被处理多次的任务 [1-9][0-9]* 个" "两步取删、不看 ZREM 结果：同一任务被多个消费者处理"
expect_regex "$f" "^dup\.twostep\.checkzrem	.*覆盖 1000 个任务，被处理多次的任务 0 个" "两步取删、只处理 ZREM 返回 1 的：不重复"
expect_regex "$f" "^dup\.lua	.*处理 1000 次，覆盖 1000 个任务，被处理多次的任务 0 个" "Lua 取删：不重复"
expect_line "$f" "100 个任务，第一个消费者取出 10 个后崩溃：2 秒内处理完成 90 个，队列里还剩 0 个，处理中集合还剩 0 个" "直接取删：崩溃取走的 10 个任务丢失"
expect_line "$f" "100 个任务，第一个消费者取出 10 个后崩溃：2 秒内处理完成 100 个，队列里还剩 0 个，处理中集合还剩 0 个" "处理中集合与回收：10 个任务在租约过期后被重新处理"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
d = {k: tuple(map(int, v)) for k, *v in re.findall(r"^(\S+)\t.*延迟 p50 (\d+) ms，p90 (\d+) ms，最大 (\d+) ms", t, re.M)}
p20, p100, p500 = d["spread.poll20"], d["spread.poll100"], d["spread.poll500"]
assert p20[0] < p100[0] < p500[0], d
assert p100[2] <= 100 + 50 and p500[2] <= 500 + 50, d          # 到期分散时，最大延迟不超过一个轮询间隔（留 50 ms 余量）
q100, q500 = d["spread.poll100.batch100"], d["spread.poll500.batch100"]
assert q100[2] <= 100 + 50, d                                   # 每轮到期约 50 个，没超过上限：和不限批量一样
assert q500[2] > 3 * 500, d                                     # 每轮到期约 250 个，超过上限 100：积压，延迟越来越大
b100, b1000 = d["burst.batch100"], d["burst.batch1000"]
assert b100[2] > 5 * b1000[2] and b100[2] >= 800, d           # 同时到期时批量上限让最后一批等多轮
print(f"通过：p50 随轮询间隔增大（20/100/500 ms → {p20[0]}/{p100[0]}/{p500[0]} ms）；轮询 500 ms、每次 100 个时积压，最大 {q500[2]} ms；同时到期时每次 100 个的最大延迟 {b100[2]} ms，每次 1000 个 {b1000[2]} ms")
PY
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
