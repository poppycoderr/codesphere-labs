#!/usr/bin/env bash
# 召回给排序设定的上限：固定标签的 nDCG、候选内最好的排序、分母取候选内还是完整标签（标准库）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，只用标准库；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python -B src/lab.py' >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" 
cat "$f" >&2
# 输入固定、顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
labels	相关性等级（固定）：{'a': 0, 'b': 1, 'c': 3, 'd': 2, 'e': 0}；相关文档 ['b', 'c', 'd']
recall.3	召回前 3 个：['a', 'b', 'e']，覆盖相关文档 1/3；排序后 ['b', 'e', 'a']
ndcg.3	召回 3 个时 nDCG@3：排序器 0.106，候选内最好的排序 0.106（上限）
recall.5	召回前 5 个：['a', 'b', 'e', 'c', 'd']，覆盖相关文档 3/3；排序后 ['b', 'c', 'd']
ndcg.5	召回 5 个时 nDCG@3：排序器 0.736，候选内最好的排序 1.000（上限）
denominator	召回 3 个时，分母只取候选内标签：排序器 1.000，候选内最好的排序 1.000；分母取完整标签：0.106
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
