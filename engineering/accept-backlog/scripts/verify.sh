#!/usr/bin/env bash
# 监听队列：服务端不 accept 时能完成多少个握手、握手完成后发请求的结果、队列满后新连接的表现、somaxconn 的封顶、Java 的默认 backlog
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python 3.14 与 temurin 25 容器，只用容器内的回环地址；约 40 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -v "$PWD/src:/src:ro" "$PY" python /src/backlog.py 5 default >"$f"
# somaxconn 是按网络命名空间设置的，这里只改这个容器的
docker run --rm --sysctl net.core.somaxconn=8 -v "$PWD/src:/src:ro" "$PY" python /src/backlog.py 1000 somaxconn_8 >>"$f"
docker run --rm -v "$PWD/src:/src:ro" "$J25" java /src/DefaultBacklog.java 2>/dev/null >>"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" "jdk25_image: $J25" "container_kernel: $(docker run --rm "$PY" uname -r)"
cat "$f" >&2
# 握手完成的个数由内核按 backlog 决定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
default.setup	listen(backlog=5)，net.core.somaxconn = 4096，tcp_abort_on_overflow = 0
default.connect	服务端不 accept，同时发起 40 个连接，2 秒后：握手完成 6 个，仍在等待 34 个
default.counters	内核计数器 ListenOverflows 增加了 = True，ListenDrops 增加了 = True
default.request	在握手完成的连接上发请求：send 返回 36 字节（成功）；随后读取：1 秒内没有任何响应（读超时）
default.new_connection	队列满了之后再来一个连接（连接超时设 1 秒）：connect 超时，耗时约 1 秒
somaxconn_8.setup	listen(backlog=1000)，net.core.somaxconn = 8，tcp_abort_on_overflow = 0
somaxconn_8.connect	服务端不 accept，同时发起 40 个连接，2 秒后：握手完成 9 个，仍在等待 31 个
somaxconn_8.counters	内核计数器 ListenOverflows 增加了 = True，ListenDrops 增加了 = True
somaxconn_8.request	在握手完成的连接上发请求：send 返回 36 字节（成功）；随后读取：1 秒内没有任何响应（读超时）
somaxconn_8.new_connection	队列满了之后再来一个连接（连接超时设 1 秒）：connect 超时，耗时约 1 秒
java.default	new ServerSocket(port) 不指定 backlog、不 accept，同时发起 80 个连接（超时 2 秒）：握手完成 51 个（java.version=25.0.4.1）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
