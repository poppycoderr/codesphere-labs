#!/usr/bin/env bash
# WebSocket 接口测试：一发一收、推送与乱序响应、关联 ID、并发 recv、超时、握手鉴权、关闭码、消息大小、异常断线与连接清理（Python 3.14、websockets 17.2）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，websockets 从 PyPI 安装到缓存目录；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check websockets==17.2 >/dev/null 2>&1 && python src/lab.py' >"$f" 2>/dev/null
write_environment "$OUT/environment.txt" "python_image: $PY" "websockets: 17.2"
cat "$f" >&2
# 所有输入固定，耗时取整到秒：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	websockets=17.2
defaults	客户端默认值：open_timeout=10，ping_interval=20，ping_timeout=20，close_timeout=10，max_size=1048576；recv() 本身没有超时参数
naive.echo	echo 服务一发一收：收到 {"id": 1, "result": "hi"}
naive.push	服务端先推送了一条通知：一发一收拿到的是 {'type': 'notice', 'text': '系统维护'}，断言 result=='hi' 失败
naive.order	先发 id=1（慢）再发 id=2（快）：第一条收到的是 id=2；按发送顺序配对会把 2 的结果当成 1 的
matched	按 id 分派：请求 1 得到 done-1，请求 2 得到 done-2；推送单独收到 notice:系统维护
concurrent.recv	两个协程同时 recv：ConcurrencyError：cannot call recv while another coroutine is already running recv or recv_streaming
timeout	服务端不回复：recv 本身没有超时，套上 asyncio.timeout(1) 后约 1 秒抛出 TimeoutError；连接仍是 OPEN
auth.missing	握手不带 Token：InvalidStatus，HTTP 401；连接根本没有建立
auth.header	握手时在请求头带 Token：连接成功，收到 ok
close.normal	正常关闭后 recv：ConnectionClosedOK，关闭码 1000
close.policy	服务端以 1008 踢掉连接：ConnectionClosedError，关闭码 1008，原因 'token expired'
close.too_big	发送 2 MiB 消息（服务端 max_size 1 MiB）：服务端以关闭码 1009 断开，原因 'frame exceeds limit of 1048576 bytes'
close.abnormal	服务端直接断开 TCP：ConnectionClosedError，没有收到关闭帧（rcvd=None）；RFC 6455 把这种情况记作 1006，它不会出现在关闭帧里
cleanup.leak	测试里 connect 之后没有关闭：服务端仍有 1 个打开的连接
cleanup.closed	显式 close（或用 async with）之后：服务端打开的连接 0 个
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
