#!/usr/bin/env bash
# TCP 字节流上的消息边界：多次写被一次读到、一条消息分两次读到、长度前缀与分隔符、没有上限的长度字段、乱序响应的配对、超时后复用连接读到上一个请求的响应、非阻塞写的部分写入
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 15 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC  src/FramingLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 各场景用固定的先后顺序与等待构造，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
merge	发送方分三次写出三条消息；接收方稍后的一次 read 得到 24 字节：{"id":1}{"id":2}{"id":3}
split	一条 34 字节的消息分两段到达；接收方的第一次 read 得到 20 字节：{"orderId":"A-1001",
length_prefix	4 字节长度加内容，readFully 读满：收到 4 条：[{"id":1}, {"id":2}, {"id":3}, {"orderId":"A-1001","amount":1999}]
delimiter	换行分隔，发出 2 条消息（第 2 条的内容里有换行）：接收方读到 3 行：{"note":"first"} ‖ {"note":"line one ‖ line two"}
length.http	把 "GET " 这 4 个字节当作长度 = 1195725856
length.tls	把 TLS 握手记录的前 4 个字节 16 03 01 02 当作长度 = 369295618
length.limit	上限 1 MB 的接收方收到一个 HTTP 请求：拒绝并关闭连接（frame length 1195725856 exceeds limit 1048576）
correlation.by_order	先发慢请求再发快请求，按到达顺序配对：慢请求拿到 result-of-fast，快请求拿到 result-of-slow
correlation.by_id	按帧里的请求编号配对：请求 7（慢）拿到 result-of-slow，请求 8（快）拿到 result-of-fast
reuse_after_timeout	第一个请求（查 alice）读超时；在同一条连接上接着发第二个请求（查 bob），读到的响应是 answer-to-balance-of-alice
partial_write	非阻塞通道一次 write 64 MB，对端不读：返回值小于 64 MB = true，缓冲区里还有数据没写出 = true
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
