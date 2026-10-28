#!/usr/bin/env bash
# 分流位置与响应路径：urllib 跟随 302/307、DNS 缓存的 TTL、HTTP 与 TCP 代理下后端看到的地址、Nginx 对 POST 的重试（Nginx 1.30.0、HAProxy 3.4.2、CoreDNS 1.14.7）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 7 个容器，internal 网络、无端口映射，每个服务限制 CPU 与内存；约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" build; find "$OUT" -mindepth 1 ! -name README.md -delete
trap 'rc=$?; docker compose logs --no-color >build/services.log 2>&1 || true; docker compose down --volumes --remove-orphans >build/cleanup.log 2>&1 || true; exit $rc' EXIT
docker compose up -d good bad authdns cache nginx tcp >build/start.log 2>&1
docker compose run --rm -T client >"$OUT/results.json" 2>build/client.log
docker compose logs --no-color >build/services.log
docker compose config --images >"$OUT/images.txt"
# 版本从容器内的程序本身读取，与 digest 一起归档
{ docker compose exec -T nginx nginx -v 2>&1; docker compose exec -T tcp haproxy -v 2>&1 | head -1
  docker compose exec -T cache /coredns -version 2>&1 | head -1
  docker compose exec -T good python3 --version 2>&1; } >"$OUT/versions.txt"
expect_regex "$OUT/versions.txt" 'nginx/1\.30\.0'
expect_regex "$OUT/versions.txt" 'HAProxy version 3\.4\.2'
expect_regex "$OUT/versions.txt" 'CoreDNS-1\.14\.7'
expect_regex "$OUT/versions.txt" 'Python 3\.14\.8'
write_environment "$OUT/environment.txt"
cat "$OUT/results.json"
