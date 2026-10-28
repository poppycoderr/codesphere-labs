#!/usr/bin/env bash
# 浏览器的 HTTP 缓存：各种缓存响应头下第二次请求是否回源、验证请求与 304、Age 对新鲜度的影响、普通刷新时子资源的处理
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Playwright 镜像（自带 Chromium，约 2.4 GB），源站是容器内的本地服务；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PW="mcr.microsoft.com/playwright/python@sha256:72bd171a9ffc2b4b59532aaa6210e21014d07093120dc25528870c0b840da1f0"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PW" sh -c '
  pip install -q --root-user-action=ignore --disable-pip-version-check --break-system-packages -r requirements.txt >/dev/null 2>&1
  cd src && python3 cache.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "playwright_image: ${PW}（v1.63.0-noble）" "playwright: 1.63.0"
cat "$f" >&2
# 请求由脚本按固定顺序发出，只统计回源次数与验证头，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	chromium=153.0.8010.12
twice.max-age	Cache-Control: max-age=600：连续 fetch 两次，源站收到 1 次；页面第二次拿到「200 body of /max-age」
twice.nothing	没有任何缓存相关的头：连续 fetch 两次，源站收到 2 次；页面第二次拿到「200 body of /nothing」
twice.heuristic	没有 Cache-Control，只有 Last-Modified（10 天前）：连续 fetch 两次，源站收到 1 次；页面第二次拿到「200 body of /heuristic」
twice.no-store	Cache-Control: no-store：连续 fetch 两次，源站收到 2 次；页面第二次拿到「200 body of /no-store」
twice.no-cache	Cache-Control: no-cache，带 ETag：连续 fetch 两次，源站收到 2 次；第二次回源带 If-None-Match = "v1"；页面第二次拿到「200 body of /no-cache」
age	max-age=600 但响应带 Age: 599，取一次、等 2 秒再取：源站收到 2 次
validate	no-cache 加 ETag，内容在第二次和第三次之间换了版本：三次拿到「200 release 1」「200 release 1」「200 release 2」；源站收到 3 次，其中带验证头的 2 次
reload	页面普通刷新：两个子资源都是 max-age=600，没标 immutable 的回源 0 次，标了 immutable 的回源 0 次
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
