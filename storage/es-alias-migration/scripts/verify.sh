#!/usr/bin/env bash
# 别名切换与迁移：reindex 之后的变更、按源版本号追平、别名的写索引、原子切换与回切（Elasticsearch 9.5.5）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：单节点 Elasticsearch 9.5.5 容器（固定 digest，堆 512 MB，不暴露端口）与 python:3.14.8-slim 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
PROJECT=csl-esalias
C=(docker compose -p "$PROJECT" -f compose.yaml)
trap 'rc=$?; "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; exit $rc' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
f="$OUT/output.tsv"
docker run --rm --network "${PROJECT}_default" -v "$PWD:/w:ro" -w /w "$PY" python src/lab.py >"$f"
write_environment "$OUT/environment.txt" "elasticsearch_image: $(sed -n 's/^ *image: //p' compose.yaml)" "python_image: $PY"
cat "$f" >&2
# 变更在 reindex 之后按固定顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	elasticsearch=9.5.5，2000 条订单，v2 把 status 从 text 改为 keyword
naive.switch	reindex 复制 2000 条后，v1 上新增 50、更新 30、删除 20，直接切别名：v2 比 v1 少 50 条、多 20 条、内容不同 30 条
naive.read	切换后经别名查已退款订单：0 条（v1 里是 30 条）
catchup.external	按源版本号把变更重放到 v2 两次，最后再来一条 o0 的旧版本：第二遍冲突 101 条（被忽略）；v2 比 v1 少 0 条、多 0 条、内容不同 0 条
catchup.plain	同样的重放不带版本号：v2 比 v1 少 0 条、多 0 条、内容不同 1 条
multi.no-write-index	别名指向 v1、v2 且都不是写索引：写入 HTTP 400（illegal_argument_exception）
multi.write-index	把 v2 设为写索引：写入落在 orders_v2；经别名按 id 查 o5 得到 2 条（两个索引各一份）
switch.atomic	一个 _aliases 请求里 remove + add：HTTP 200，别名现在指向 ['orders_v2']
switch.rollback	回切：HTTP 200，别名指向 ['orders_v1']；v1 没有删除，回切只是改别名
EXPECTED
log "全部通过，输出在 $OUT"
