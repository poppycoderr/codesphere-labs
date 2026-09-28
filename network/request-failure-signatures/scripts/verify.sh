#!/usr/bin/env bash
# 请求失败在客户端的样子：DNS 失败、无路由、邻居解析失败、端口无人监听、SYN 被丢弃、HTTP 5xx，以及 ping 通但端口不通
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：一个 JDK 21 服务端容器、一个 netshoot 容器（NET_ADMIN，只改它自己命名空间的路由与 iptables）、JDK 21 客户端；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
NS=$("${C[@]}" ps -q netns)
x() { docker exec "$NS" sh -c "$1"; }
log "在客户端命名空间里配置：10.201.0.0/16 为 unreachable 路由；到服务端 8081 端口的 SYN 丢弃"
x "ip route replace unreachable 10.201.0.0/16"
x "iptables -C OUTPUT -p tcp -d 172.31.7.10 --dport 8081 -j DROP 2>/dev/null || iptables -A OUTPUT -p tcp -d 172.31.7.10 --dport 8081 -j DROP"
{
  echo "## ip route get"
  for ip in 172.31.7.10 172.31.7.99 10.201.0.5; do x "ip route get $ip 2>&1 | head -1" || true; done
  echo "## ip route（非默认）"
  x "ip route | grep -v '^default'"
  echo "## iptables OUTPUT"
  x "iptables -S OUTPUT"
} >"$OUT/netns-config.txt"
docker run --rm --network "container:$NS" -v "$PWD/src:/w/src:ro" "$JDK_IMAGE" java /w/src/Client.java >"$OUT/output.tsv" 2>&1
{
  echo "## ip neigh（客户端访问之后）"
  x "ip neigh show 172.31.7.99; ip neigh show 172.31.7.98; ip neigh show 172.31.7.10"
  echo "## ping 服务端（它的 9090 端口没有进程监听）"
  x "ping -c 2 -W 1 172.31.7.10 | tail -2"
  echo "## curl 9090"
  x "curl -s -o /dev/null -w '%{http_code} %{errormsg}' --connect-timeout 2 http://172.31.7.10:9090/ || true"
  echo
} >"$OUT/diagnostics.txt"
x "ip -V" >"$OUT/iproute2-version.txt"
write_environment "$OUT/environment.txt" "jdk_image: $JDK_IMAGE" "netshoot: v0.16（固定 digest）"
f="$OUT/output.tsv"; d="$OUT/diagnostics.txt"
cat "$OUT/netns-config.txt" "$f" "$d" >&2
expect_regex "$f" "^ok\.http	.*→ HTTP 200，" "正常请求"
expect_regex "$f" "^http_503\.http	.*→ HTTP 503，耗时 < 50 ms$" "HTTP 5xx：拿到的是状态码，不是异常"
expect_regex "$f" "^dns_nxdomain\.http	.*→ ConnectException.*UnresolvedAddressException" "DNS 失败：HttpClient 报 ConnectException，原因是 UnresolvedAddressException"
expect_regex "$f" "^dns_nxdomain\.socket	.*→ UnknownHostException" "DNS 失败：InetAddress 报 UnknownHostException"
expect_regex "$f" "^route_unreachable\.http	.*NoRouteToHostException\(No route to host\)，耗时 < 50 ms$" "无路由：立即 NoRouteToHostException"
expect_regex "$f" "^neighbor_failed\.http	.*→ HttpConnectTimeoutException.*耗时 约 2" "邻居解析失败：在 2 秒连接超时下看起来和超时一样"
expect_regex "$f" "^neighbor_failed\.socket	.*→ NoRouteToHostException\(No route to host\)，耗时 约 [3-4]" "邻居解析失败：约 3 秒后 No route to host"
expect_regex "$f" "^port_closed\.http	.*→ ConnectException.*，耗时 < 50 ms$" "端口无人监听：HttpClient 立即 ConnectException"
expect_regex "$f" "^port_closed\.socket	.*→ ConnectException\(Connection refused\)，耗时 < 50 ms$" "端口无人监听：Socket 立即 Connection refused"
expect_regex "$f" "^syn_dropped\.http	.*→ HttpConnectTimeoutException.*耗时 约 2" "SYN 被丢弃：等满 2 秒连接超时"
expect_regex "$f" "^syn_dropped\.socket	.*→ SocketTimeoutException\(Connect timed out\)，耗时 约 10" "SYN 被丢弃：Socket 等满 10 秒"
expect_regex "$d" "172\.31\.7\.99 dev eth0 +(FAILED|INCOMPLETE)" "邻居表里 172.31.7.99 为 FAILED 或 INCOMPLETE"
expect_regex "$d" "2 packets transmitted, 2 received" "ping 服务端是通的"
expect_regex "$d" "^000 .*(Connection refused|Failed to connect)" "同时 9090 端口连接被拒绝"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
