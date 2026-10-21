#!/usr/bin/env bash
# 偏好分数的契约：成对损失只看差值、softmax 依赖比较集合、偏好数据里的长度混杂被评分器学走（标准库）
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
shift	分数 (2, 1) 的成对损失 0.313262；都加 100 后 0.313262；都乘 2 后 0.126928
sigmoid	单个分数 2 与 102 的 sigmoid：0.880797 与 1.000000，训练目标却认为这两组分数一样好
softmax	同一个分数 2：与 [1] 比较时 softmax 0.731059，与 [1, 2] 比较时 0.422319
bias.confounded	训练数据里被选中的回答总是更长：学到的权重（约定检查, 长度）= (1.195, 1.854)；保留样本「短而满足约定」得分 3.049，「长而不满足」得分 11.126，偏好 后者
bias.balanced	训练数据里长短与选择无关：学到的权重（约定检查, 长度）= (2.897, 0.033)；保留样本「短而满足约定」得分 2.930，「长而不满足」得分 0.196，偏好 前者
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
