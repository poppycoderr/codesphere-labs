#!/usr/bin/env bash
# 跨源请求在真实浏览器里的表现：没有跨源头时请求是否到达接口、预检、带 Cookie 时的要求、可读的响应头、预检缓存
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Playwright 镜像（自带 Chromium，约 2.4 GB），页面与接口都是容器内的本地服务；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PW="mcr.microsoft.com/playwright/python@sha256:72bd171a9ffc2b4b59532aaa6210e21014d07093120dc25528870c0b840da1f0"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PW" sh -c '
  pip install -q --root-user-action=ignore --disable-pip-version-check --break-system-packages -r requirements.txt >/dev/null 2>&1
  cd src && python3 cors.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "playwright_image: $PW（v1.63.0-noble）" "playwright: 1.63.0"
cat "$f" >&2
# 请求由脚本按固定顺序发出，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	chromium=153.0.8010.12
tool.direct	不经过浏览器，用脚本直接请求同一个接口（没有任何跨源头）：{"ok": true, "cookie": ""}
get.no_header	页面里 fetch 这个接口：脚本拿到 TypeError，读不到任何内容；接口收到这个 GET 1 次
get.console	浏览器控制台的报错里提到 Access-Control-Allow-Origin = True
post.simple	页面里发一个 text/plain 的 POST 到 /transfer（没有跨源头）：脚本拿到 TypeError，读不到任何内容；接口收到 POST 1 次，转账次数 = 1
post.json	换成 application/json（接口不处理 OPTIONS）：脚本拿到 TypeError，读不到任何内容；接口收到 OPTIONS 1 次、POST 0 次
post.json_ok	接口正确应答预检并带上跨源头：读到响应（状态 200），X-Request-Id = null；接口收到 OPTIONS 1 次、POST 1 次
allow.star	响应带 Access-Control-Allow-Origin: *：读到响应（状态 200），X-Request-Id = null
allow.expose	响应另外带 Access-Control-Expose-Headers: X-Request-Id：读到响应（状态 200），X-Request-Id = req-42
cred.star	请求带上 Cookie（credentials: include），响应是 Allow-Origin: *：脚本拿到 TypeError，读不到任何内容
cred.exact_only	响应写明了源，但没有 Allow-Credentials：脚本拿到 TypeError，读不到任何内容
cred.exact	响应写明了源，并带 Allow-Credentials: true：读到响应（状态 200），X-Request-Id = null
cred.sent	上面三次请求，接口都收到了 = True
preflight.cache	预检响应带 Access-Control-Max-Age: 600，连续发两次 POST：接口收到 OPTIONS 1 次、POST 2 次
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
