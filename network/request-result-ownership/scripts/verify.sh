#!/usr/bin/env bash
# fetch 的错误语义、回调适配的单次完成、迟到响应的所有权与取消（Node 24.21.0 内置 fetch + 本地 HTTP 服务）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 node:24.21.0-slim 容器；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="node:24.21.0-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$IMG" node src/run.mjs 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
fetch.404	404 页面：直接 r.json() 的写法 失败（SyntaxError）；先检查 r.ok 的写法 HTTP 404，按失败处理
fetch.404-json	404 但响应体是 JSON：直接 r.json() 的写法 成功（{"error":"订单不存在"}）；先检查 r.ok 的写法 HTTP 404，按失败处理
fetch.500	500 页面：直接 r.json() 的写法 失败（SyntaxError）；先检查 r.ok 的写法 HTTP 500，按失败处理
fetch.204	204 No Content：直接 r.json() 的写法 失败（SyntaxError）；分开处理 204，没有响应体
fetch.network	连不上服务端：fetch 才会 reject，TypeError（fetch failed）
callback.once	请求成功、但成功回调里抛错：then(onOk).catch(onFail) 依次调用了 onOk、onFail(渲染失败)；then(onOk, onFail) 调用了 onOk、调用方收到 渲染失败
stale.naive	先搜 a（300ms）再搜 ab（50ms），按到达顺序直接显示：最终显示 a；过程 显示 ab、显示 a；服务端完成了 2 个搜索
stale.generation	先搜 a（300ms）再搜 ab（50ms），代际标记丢弃过期结果：最终显示 ab；过程 显示 ab、丢弃过期的 a；服务端完成了 2 个搜索
stale.abort	先搜 a（300ms）再搜 ab（50ms），AbortController 取消上一个请求：最终显示 ab；过程 a AbortError、显示 ab；服务端完成了 2 个搜索
abort.kinds	AbortSignal.timeout(50) 触发：TimeoutError；主动 abort()：AbortError（调用方可以据此区分「超时」与「用户不要了」）
node	v24.21.0
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
