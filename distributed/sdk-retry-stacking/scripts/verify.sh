#!/usr/bin/env bash
# SDK 自带重试与应用层重试叠加：本地假服务计数（openai 3.35.1 Python SDK，不调用真实服务）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，openai 从 PyPI 安装到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" "openai: 3.35.1"
cat "$f" >&2
# 状态码与计数是确定的，耗时只输出不少于某值的判断：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	openai=3.35.1
defaults	DEFAULT_MAX_RETRIES=2；DEFAULT_TIMEOUT=Timeout(connect=5.0, read=600, write=600, pool=600)
sdk.default	返回 503，只用 SDK 默认设置：一次调用发出 3 个请求，最后抛出 InternalServerError；请求头 x-stainless-retry-count 依次为 ['0', '1', '2']
stack	应用层再包一层「最多 3 次」的重试：一次业务调用发出 9 个请求
sdk.zero	SDK max_retries=0，只保留应用层 3 次：3 个请求
status.409	返回 409：3 个请求，抛出 ConflictError
status.400	返回 400：1 个请求，抛出 BadRequestError
should_retry	返回 503 但带 x-should-retry: false：1 个请求
retry_after	返回 429 且 retry-after: 1：3 个请求，总耗时不少于 2 秒：True
timeout	服务端 1 秒才响应，SDK timeout=0.3：3 个请求都到达了服务端，最后抛出 APITimeoutError；客户端放弃之后，服务端处理完的有 3 个
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
