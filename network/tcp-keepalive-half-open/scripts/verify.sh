#!/usr/bin/env bash
# 对端还活着吗：网络断开后空闲连接、默认与调过参数的 keepalive、写入时的重传与 TCP_USER_TIMEOUT、对端进程冻结时的 keepalive 与应用层心跳、未认证连接的超时
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：两个 netshoot v0.16 容器（固定 digest；客户端容器有 NET_ADMIN，只改自己命名空间的 iptables），约 40 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
trap 'rc=$?; "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; exit $rc' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d >/dev/null 2>&1 || fail "容器启动失败"
SRV=$("${C[@]}" ps -q srv); CLI=$("${C[@]}" ps -q cli)
c() { docker exec "$CLI" sh -c "$1"; }
T="python3 /w/src/ka.py"
for pm in "9001 pingpong" "9002 freeze" "9003 pingpong" "9004 auth-timeout"; do docker exec -d "$SRV" sh -c "$T serve $pm"; done
sleep 1
f="$OUT/output.tsv"; : >"$f"
o() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
within() { awk -v x="$1" -v lo="$2" -v hi="$3" 'BEGIN{print (x>=lo && x<=hi) ? "true" : "false"}'; }

o "sysctl" "默认 keepalive：空闲 $(c 'cat /proc/sys/net/ipv4/tcp_keepalive_time') 秒后开始探测，间隔 $(c 'cat /proc/sys/net/ipv4/tcp_keepalive_intvl') 秒，$(c 'cat /proc/sys/net/ipv4/tcp_keepalive_probes') 次无应答判定断开；tcp_retries2 = $(c 'cat /proc/sys/net/ipv4/tcp_retries2')"

c "$T partition" >"$OUT/partition.txt"
r() { awk -v k="$1" '$1==k{print $2}' "$OUT/partition.txt"; }
t() { awk -v k="$1" '$1==k{print $3}' "$OUT/partition.txt"; }
o "idle" "网络断开（客户端命名空间丢弃双向报文）后，不开 keepalive 的空闲连接：10 秒内结果 $(r idle)；第 6 秒时 5 个连接中仍处于 ESTABLISHED 的有 $(awk '$1=="established-at-6s"{print $2}' "$OUT/partition.txt") 个"
o "keepalive.default" "只打开 SO_KEEPALIVE、用默认参数：10 秒内结果 $(r keepalive-default)"
o "keepalive.tuned" "TCP_KEEPIDLE=2、TCP_KEEPINTVL=1、TCP_KEEPCNT=3：结果 $(r keepalive-tuned)，用时在 4 到 7 秒之间=$(within "$(t keepalive-tuned)" 4 7)"
o "write" "断开后写入一个 PING 再等回应：10 秒内结果 $(r write)（数据在重传，还没有放弃）"
o "write.user-timeout" "同样写入，设 TCP_USER_TIMEOUT=3000ms：结果 $(r write-user-timeout)，用时在 2.5 到 5 秒之间=$(within "$(t write-user-timeout)" 2.5 5)"

c "$T frozen" >"$OUT/frozen.txt"
fr() { awk -v k="$1" '$1==k{print $2}' "$OUT/frozen.txt"; }
o "frozen.keepalive" "服务端进程被 SIGSTOP 冻结，客户端用同样的短 keepalive 参数空等 8 秒：结果 $(fr keepalive)（内核仍在回应探测）"
o "frozen.heartbeat" "接着发应用层心跳 PING，1 秒内等 PONG：结果 $(fr heartbeat)"

c "$T unauth" >"$OUT/unauth.txt"
ua() { awk -v k="$1" '$1==k{print $2}' "$OUT/unauth.txt"; }
o "unauth" "客户端连上后什么也不发：服务端没有首包超时时，5 秒内客户端看到 $(ua no-timeout)；服务端设 2 秒首包超时时看到 $(ua auth-timeout)，用时在 1.5 到 3 秒之间=$(within "$(awk '$1=="auth-timeout"{print $3}' "$OUT/unauth.txt")" 1.5 3)"
write_environment "$OUT/environment.txt" "netshoot: v0.16（固定 digest，python3 $(c 'python3 --version 2>&1 | cut -d" " -f2')）" "kernel: $(c 'uname -r')"
cat "$f" >&2
# 时间只断言范围，原始秒数在 partition.txt、frozen.txt、unauth.txt
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
sysctl	默认 keepalive：空闲 7200 秒后开始探测，间隔 75 秒，9 次无应答判定断开；tcp_retries2 = 15
idle	网络断开（客户端命名空间丢弃双向报文）后，不开 keepalive 的空闲连接：10 秒内结果 等满无结果；第 6 秒时 5 个连接中仍处于 ESTABLISHED 的有 3 个
keepalive.default	只打开 SO_KEEPALIVE、用默认参数：10 秒内结果 等满无结果
keepalive.tuned	TCP_KEEPIDLE=2、TCP_KEEPINTVL=1、TCP_KEEPCNT=3：结果 ETIMEDOUT，用时在 4 到 7 秒之间=true
write	断开后写入一个 PING 再等回应：10 秒内结果 等满无结果（数据在重传，还没有放弃）
write.user-timeout	同样写入，设 TCP_USER_TIMEOUT=3000ms：结果 ETIMEDOUT，用时在 2.5 到 5 秒之间=true
frozen.keepalive	服务端进程被 SIGSTOP 冻结，客户端用同样的短 keepalive 参数空等 8 秒：结果 等满无结果（内核仍在回应探测）
frozen.heartbeat	接着发应用层心跳 PING，1 秒内等 PONG：结果 等满无结果
unauth	客户端连上后什么也不发：服务端没有首包超时时，5 秒内客户端看到 等满无结果；服务端设 2 秒首包超时时看到 EOF，用时在 1.5 到 3 秒之间=true
EXPECTED
log "全部通过，输出在 ${OUT}"
