#!/usr/bin/env bash
# 跨分片分页：offset 归并的读取量、平均分摊 offset 的错误、单列游标遇到同值的遗漏与重复、复合游标、翻页期间插入（纯计算模拟）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，只用标准库；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY"
cat "$f" >&2
# 数据由固定种子生成，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
data	4 个分片、每片 5000 行、共 20000 行；created_at 只到秒，与前一行同值的行有 17999 个
offset.1	第 1 页：每个分片取前 20 行（不足则取全部），共读取 80 行、归并后丢弃 60 行；结果正确：True
offset.50	第 50 页：每个分片取前 1000 行（不足则取全部），共读取 4000 行、归并后丢弃 3980 行；结果正确：True
offset.500	第 500 页：每个分片取前 10000 行（不足则取全部），共读取 20000 行、归并后丢弃 19980 行；结果正确：True
offset.split	把第 50 页的 offset 平均分给 4 个分片、每片取 5 行：与正确结果相同的行 5 / 20
cursor.strict	只按 created_at，条件 created_at < 上一页最后一行：遍历得到 15978 行，遗漏 4022 行，重复 0 行
cursor.inclusive	只按 created_at，条件 created_at <= 上一页最后一行（排除那一行本身）：遍历得到 40020 行，遗漏 17195 行，重复 37215 行；已在同值的行之间反复翻页，实验在遍历量超过总行数两倍时停止
cursor.composite	复合游标 (created_at, id) < 上一页最后一行：遍历得到 20000 行，遗漏 0 行，重复 0 行；与全局排序逐行一致：True
cursor.cost	复合游标每页每个分片最多取 20 行，遍历 1000 页共读取 79865 行；同样遍历用 offset 需要读取 17510000 行
insert.offset	读完第 1 页后插入一条更新的数据：offset 取第 2 页，第一行与第 1 页最后一行重复：True
insert.cursor	复合游标取第 2 页：与第 1 页重复的行 0 个，紧接在第 1 页之后：True
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
