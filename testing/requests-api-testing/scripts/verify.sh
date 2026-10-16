#!/usr/bin/env bash
# 用 requests 写接口测试：查询参数编码、data 与 json、默认没有超时、不检查状态码、会话与连接复用（Python 3.14、requests 2.34.2）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，requests 从 PyPI 安装到缓存目录；约 20 秒（含两次 3 秒的慢请求）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check requests==2.34.2 >/dev/null 2>&1 && python src/lab.py' >"$f" 2>/dev/null
write_environment "$OUT/environment.txt" "python_image: $PY" "requests: 2.34.2"
cat "$f" >&2
# 所有输入固定，耗时取整到秒：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	python=3.14.8 requests=2.34.2
params.fstring	自己拼 URL「?q=a b&c」：服务端收到 {'q': ['a b'], 'c': [''], 'tag': ['x']}
params.dict	params={'q': 'a b&c', 'tag': ['x', 'y']}：查询串 q=a+b%26c&tag=x&tag=y，服务端收到 {'q': ['a b&c'], 'tag': ['x', 'y']}
body.data_dict	data=dict：Content-Type application/x-www-form-urlencoded，请求体 sku=A1&qty=2
body.json	json=dict：Content-Type application/json，请求体 {"sku": "A1", "qty": 2}
body.data_str	data=json.dumps(...)：Content-Type None，请求体 {"sku": "A1", "qty": 2}；没有声明 JSON，按 Content-Type 分派的服务端不会按 JSON 解析
body.data_nested	data 里放嵌套结构：请求体 items=sku&items=sku
timeout.none	不设 timeout 请求一个 3 秒才响应的接口：等了约 3 秒；requests 默认不设超时，对方一直不响应就一直等
timeout.set	timeout=1：约 1 秒后抛出 ReadTimeout
status.unchecked	500 响应：不抛异常，resp.ok=False；只断言 resp.json() 有内容的测试照样通过
status.raise	raise_for_status()：HTTPError：500 Server Error: Internal Server Error
session.none	不用 Session：先登录再访问 /me，状态 401
session.cookie	Session：登录后访问 /me，状态 200；7 次请求用了 1 个 TCP 连接
session.connections	不用 Session 发 7 次请求：用了 7 个 TCP 连接
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
