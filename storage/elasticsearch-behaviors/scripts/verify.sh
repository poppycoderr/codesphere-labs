#!/usr/bin/env bash
# Elasticsearch 9.5.3：分词、text 与 keyword、路由、近实时刷新、terms 聚合排名误差、默认线程池；调小 search 线程池后重聚合被拒绝
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 Elasticsearch 容器（2 CPU、2 GB）与 JDK 21 客户端容器；约 3 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
CP="/cache/m2/jackson-databind-2.22.3.jar:/cache/m2/jackson-core-2.22.3.jar:/cache/m2/jackson-annotations-2.22.jar"
maven_jar com.fasterxml.jackson.core:jackson-databind:2.22.3 >/dev/null
maven_jar com.fasterxml.jackson.core:jackson-core:2.22.3 >/dev/null
maven_jar com.fasterxml.jackson.core:jackson-annotations:2.22 >/dev/null
C=(docker compose -f compose.yaml)
P=(docker compose -f compose.yaml -f compose.pool.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
log "默认配置：分词、映射、路由、刷新、聚合"
"${C[@]}" up -d --wait >/dev/null 2>&1
java_in_network "$("${C[@]}" ps -q es)" -cp "$CP" src/EsBehaviors.java basics >"$OUT/basics.tsv"
"${C[@]}" exec -T es curl -s 'http://127.0.0.1:9200/' | grep '"number"' | tr -d ' ,' >"$OUT/version.txt"
log "调小 search 线程池：size=2，queue_size=10"
"${P[@]}" up -d --wait --force-recreate >/dev/null 2>&1
java_in_network "$("${P[@]}" ps -q es)" -cp "$CP" src/EsBehaviors.java pool >"$OUT/pool.tsv"
write_environment "$OUT/environment.txt" "elasticsearch: 9.5.3（compose.yaml 固定 digest，2 CPU、2 GB，堆 1 GB）" "jackson-databind: 2.22.3"
cat "$OUT/basics.tsv" "$OUT/pool.tsv" "$OUT/version.txt" >&2
b="$OUT/basics.tsv"
expect_line "$OUT/version.txt" '"number":"9.5.3"' "Elasticsearch 版本"
expect_line "$b" 'standard：Elasticsearch 9 支持向量检索 in production → ["elasticsearch", "9", "支", "持", "向", "量", "检", "索", "in", "production"]' "standard 分析器把中文切成单字"
expect_line "$b" 'english：Running searches quickly → ["run", "search", "quickli"]' "english 分析器做词干还原"
expect_regex "$b" '^term\.text	.*hits=0$' "term 查 text 字段查不到"
expect_regex "$b" '^term\.keyword	.*hits=1$' "term 查 keyword 子字段能查到"
expect_regex "$b" '^agg\.text	对 title（text）做 terms 聚合：HTTP 400' "对 text 字段聚合报错"
expect_regex "$b" '^routing\.search	\?routing=user-42 搜索：_shards\.total=1，' "带路由只查一个分片"
expect_line "$b" "refresh_interval=30s：写入 result=created；立即搜索 hits=0；按 ID GET found=true；POST _refresh 后搜索 hits=1" "近实时：刷新前搜不到，GET 能拿到"
expect_regex "$b" '^nrt\.wait_for	.*forced_refresh=false，紧接着搜索 hits=2$' "refresh=wait_for 不强制刷新，写完即可搜到"
expect_line "$b" "?refresh=true 写入：响应 forced_refresh=true" "refresh=true 强制刷新"
expect_regex "$OUT/pool.tsv" '^pool\.reason	第一个 429 的原因：es_rejected_execution_exception' "429 的原因是线程池拒绝"
python3 - "$OUT" <<'PY'
import re, sys
out = sys.argv[1]
b = open(f"{out}/basics.tsv", encoding="utf-8").read()
p = open(f"{out}/pool.tsv", encoding="utf-8").read()
def top(key):
    m = re.search(rf"^agg\.{key}\t.*前 5 \[(.*?)\]；doc_count_error_upper_bound=(\d+)，sum_other_doc_count=(\d+)", b, re.M)
    return m[1].split(", "), int(m[2]), int(m[3])
truth = re.search(r"^agg\.truth\t.*真实前 5：\[(.*?)\]", b, re.M)[1].split(", ")
d, s1000, single = top("default"), top("shard_size_1000"), top("single_shard")
assert d[0] != truth and d[1] > 0, (d, truth)                     # 默认 shard_size：排名出错，误差上界大于 0
assert s1000[0] == truth and s1000[1] == 0, (s1000, truth)         # shard_size=1000：与真实结果一致
assert single[0] == truth and single[1] == 0, (single, truth)      # 单分片：与真实结果一致
size, queue = map(int, re.search(r"^pool\.default\t.*size=(\d+)，queue_size=(\d+)", b, re.M).groups())
assert queue == 1000 * size, (size, queue)                        # 9.x 默认队列长度 = 1000 × 线程数
st = dict((int(k), int(v)) for k, v in re.findall(r"(\d{3})=(\d+)", re.search(r"^pool\.status\t(.*)$", p, re.M)[1]))
rej = int(re.search(r"rejected 增加 (\d+)", p)[1])
assert st.get(429, 0) > 0 and rej > st[429], (st, rej)           # 被拒绝的分片任务多于被拒绝的请求
print(f"通过：默认 shard_size 排名出错（误差上界 {d[1]}），shard_size=1000 与单分片一致；默认线程池 size={size}、queue_size={queue}；线程池打满时 {st}，rejected +{rej}")
PY
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
