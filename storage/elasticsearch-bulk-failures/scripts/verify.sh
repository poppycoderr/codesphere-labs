#!/usr/bin/env bash
# bulk 返回 200 也可能有失败：逐项错误、只重试失败项、自动 ID 整批重试的重复、create 冲突、外部版本号（Elasticsearch 9.5.5）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：单节点 Elasticsearch 9.5.5 容器（固定 digest，堆 512 MB，不暴露端口）与 python:3.14.8-slim 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
PROJECT=cs-esbulk
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
f="$OUT/output.tsv"
docker run --rm --network "${PROJECT}_default" -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
{ "${C[@]}" config --images | sed "s/^/image: /"; echo "client_image: $PY"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "python_image: $PY" 
cat "$f" >&2
# 会话之间的交错由代码顺序固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	elasticsearch=9.5.5
partial.http	一次 bulk 5 条：HTTP 状态 200，响应顶层 errors=True
partial.items	逐项结果：['o1:201', 'o2:400(document_parsing_exception)', 'o3:201', 'o4:400(strict_dynamic_mapping_exception)', 'o5:201']
partial.count	只看 HTTP 状态的客户端认为 5 条都写入了，索引里实际 3 条
retry.failed_only	修正数据后只重试失败的 2 条（带原来的 _id）：['o2:201', 'o4:201']；索引里 5 条
retry.whole_auto_id	不指定 _id、整批重试：B1 2 条，B3 2 条，B2 1 条
create.conflict	用 create 重放：HTTP 200，['c1:409(version_conflict_engine_exception)', 'c2:201']；c1 的 qty 仍是 1
order.lww	不带版本号：先写 shipped，延迟的 paid 后到，最终状态 paid
order.external	用源系统的版本号（version_type=external）：版本 2 的旧消息后到，HTTP 409（version_conflict_engine_exception），最终状态 shipped，_version=3
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
