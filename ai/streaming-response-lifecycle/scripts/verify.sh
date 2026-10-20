#!/usr/bin/env bash
# 流式响应的生命周期：SSE 分帧、客户端断开后异步与同步生成器是否继续、生成中途失败时客户端看到什么（Python 3.14、FastAPI 0.156、uvicorn 0.54）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，依赖从 PyPI 安装到缓存目录，服务端只监听容器内回环；约 15 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" "fastapi: 0.156.2" "uvicorn: 0.54.0" "httpx: 0.28.1"
cat "$f" >&2
# 生成器按固定间隔吐出 token，断开与失败的时机由客户端控制，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	fastapi=0.156.2 uvicorn=0.54.0 httpx=0.28.1
frames	服务端写了 4 段，客户端按 SSE 规则解析出 3 个事件：message:'第一段\n第二段' | message:'多行内容第一行\n多行内容第二行' | done:'{}'
disconnect.async	异步生成器，客户端读到 3 个后断开：断开时服务端已生成 3 个，1.5 秒后共 3 个（共 20 个）；finally 执行=True，收到 CancelledError=True
disconnect.sync	同步（线程池）生成器，客户端读到 3 个后断开：断开时服务端已生成 3 个，1.5 秒后共 4 个（共 20 个）；finally 执行=False
fail.implicit	生成到一半抛异常：状态码 200，客户端收到 ['data: token-0', 'data: token-1']，读取时的异常 RemoteProtocolError
fail.explicit	把失败写成一个错误事件：状态码 200，事件 [('message', 'token-0'), ('message', 'token-1'), ('error', '{"code": "backend_failed", "partial": true}')]
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
