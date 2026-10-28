#!/usr/bin/env bash
# 翻到很深的页：from+size 上限、按 _id 排序、search_after 的决胜字段、翻页之间的写入、PIT 快照与关闭（Elasticsearch 9.5.5）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：单节点 Elasticsearch 9.5.5 容器（固定 digest，堆 512 MB，不暴露端口）与 python:3.14.8-slim 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
PROJECT=csl-esdeep
C=(docker compose -p "$PROJECT" -f compose.yaml)
trap 'rc=$?; "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; exit $rc' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
f="$OUT/output.tsv"
docker run --rm --network "${PROJECT}_default" -v "$PWD:/w:ro" -w /w "$PY" python src/lab.py >"$f"
write_environment "$OUT/environment.txt" "elasticsearch_image: $(sed -n 's/^ *image: //p' compose.yaml)" "python_image: $PY"
cat "$f" >&2
# 写入发生在固定的页号之后，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	elasticsearch=9.5.5，3 个主分片，12000 条文档，score 只有 100 个值
from.limit	from=9990,size=10：HTTP 200；from=9991,size=10：HTTP 400（illegal_argument_exception）
sort.id	按 _id 排序：HTTP 400，被拒绝（_id 的 fielddata 默认禁用，开关是 indices.id_field_data.enabled）
after.no-tiebreaker	search_after 只按 score 排序：共 10000 条，不同的 10000 条，重复 0 条，遗漏 2000 条
after.tiebreaker	search_after 按 score、pid 排序：共 12000 条，不同的 12000 条，重复 0 条，遗漏 0 条
write.no-pit	不用 PIT、第 5 页后改了 4 个文档的 score：共 12000 条，不同的 11998 条，重复 2 条，遗漏 2 条
write.pit	用 PIT、同样的写入：共 12000 条，不同的 12000 条，重复 0 条，遗漏 0 条
pit.business-sort	用 PIT、只按 score 排序（自动加 _shard_doc 决胜）：共 12000 条，不同的 12000 条，重复 0 条，遗漏 0 条
pit.id	PIT 翻页过程中返回的 ID 与打开时相同=True
pit.close	关闭 PIT：succeeded=True，释放 3 个分片上下文；打开的搜索上下文 3 → 0
pit.after-close	关闭后继续使用：HTTP 404（search_context_missing_exception）
EXPECTED
log "全部通过，输出在 $OUT"
