#!/usr/bin/env bash
# 模型输出的结构契约：语法、Schema、领域三层；Pydantic 宽松与严格模式；修复改变含义；重试预算（Python 3.14、Pydantic 2.14）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，pydantic 从 PyPI 安装到缓存目录；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" "pydantic: 2.14.1"
cat "$f" >&2
# 模型输出是写死的样本，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	pydantic=2.14.1
lax.str_number	qty 是字符串 "3"，默认（宽松）模式：通过：{"sku":"A100","qty":3,"price_cents":1999}
strict.str_number	qty 是字符串 "3"，strict + extra=forbid + 范围：拒绝：qty: int_type
lax.float_whole	qty 是 3.0，默认（宽松）模式：通过：{"sku":"A100","qty":3,"price_cents":1999}
strict.float_whole	qty 是 3.0，strict + extra=forbid + 范围：拒绝：qty: int_type
lax.float_frac	qty 是 3.5，默认（宽松）模式：拒绝：qty: int_from_float
strict.float_frac	qty 是 3.5，strict + extra=forbid + 范围：拒绝：qty: int_type
lax.bool	qty 是 true，默认（宽松）模式：通过：{"sku":"A100","qty":1,"price_cents":1999}
strict.bool	qty 是 true，strict + extra=forbid + 范围：拒绝：qty: int_type
lax.extra	多了一个 discount_pct=90，默认（宽松）模式：通过：{"sku":"A100","qty":1,"price_cents":1999}
strict.extra	多了一个 discount_pct=90，strict + extra=forbid + 范围：拒绝：discount_pct: extra_forbidden
lax.negative	qty 是 -2，默认（宽松）模式：通过：{"sku":"A100","qty":-2,"price_cents":1999}
strict.negative	qty 是 -2，strict + extra=forbid + 范围：拒绝：qty: greater_than
parse.fenced	包在代码块里：JSON 语法错误：Expecting value（第 0 个字符）
parse.prose	前面有一句话：JSON 语法错误：Expecting value（第 0 个字符）
parse.truncated	输出被截断：JSON 语法错误：Unterminated string starting at（第 26 个字符）
parse.array	返回了数组：不是 JSON 对象，而是 list
parse.first_brace	「取第一个左括号到第一个右括号」的启发式，从带嵌套说明的文本里取到：'{"note": "见 {附件}'
domain.ok	Schema 通过的 {"sku": "A100", "qty": 2, "price_cents": 1999}：领域校验 通过
domain.price	Schema 通过的 {"sku": "A100", "qty": 2, "price_cents": 1}：领域校验 价格 1 与目录价 1999 不符
domain.unknown	Schema 通过的 {"sku": "Z999", "qty": 2, "price_cents": 1999}：领域校验 未知商品 Z999
repair.original	原始输出：拒绝：qty: int_type
repair.result	修复后的输出：通过：{"sku":"A100","qty":2,"price_cents":1999}；领域校验 通过
repair.guard	对比修复前后应保持不变的字段：['sku'] 被改动，拒绝这次修复
retry.2	最多尝试 2 次：语法错误 → Schema：qty: greater_than → 放弃，返回明确的失败
retry.3	最多尝试 3 次：语法错误 → Schema：qty: greater_than → 成功
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
