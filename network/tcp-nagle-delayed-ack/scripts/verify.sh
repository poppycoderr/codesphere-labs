#!/usr/bin/env bash
# 小包为什么等了 40 毫秒：写、写、读遇上 Nagle 与延迟确认；TCP_NODELAY、合并写、sendmsg 的往返时间与数据包数
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：两个 netshoot v0.16 容器（固定 digest；客户端容器有 NET_RAW、NET_ADMIN，只用于抓包），约 30 秒（不含拉取镜像）
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
T="python3 /w/src/nagle.py"
for p in 9001 9002 9003 9004; do docker exec -d "$SRV" sh -c "$T serve $p 0"; done
docker exec -d "$CLI" sh -c "tcpdump -i eth0 --immediate-mode -U -n -w /tmp/all.pcap 'tcp and dst host 172.31.11.10' >/dev/null 2>&1"
sleep 1.5
f="$OUT/output.tsv"; : >"$f"
o() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
WARM=20; N=30
declare -a MED
i=0
for pm in "9001 nagle" "9002 nodelay" "9003 joined" "9004 sendmsg"; do
  set -- $pm
  line=$(c "$T client $1 $2 $WARM $N")
  echo "${2}: ${line}" >>"$OUT/timings.txt"
  MED[$i]=${line%% *}; i=$((i + 1))
done
sleep 1; c "pkill -INT tcpdump; while pgrep -x tcpdump >/dev/null; do sleep 0.1; done"
# 客户端发往服务端、带数据的报文数（去掉三次握手与纯 ACK）
pk() { c "tcpdump -n -r /tmp/all.pcap 'dst port $1 and (((ip[2:2] - ((ip[0]&0xf)<<2)) - ((tcp[12]&0xf0)>>2)) != 0)' 2>/dev/null | wc -l"; }
for p in 9001 9002 9003 9004; do echo "$p 全部 $(c "tcpdump -n -r /tmp/all.pcap 'dst port $p' 2>/dev/null | wc -l") 带数据 $(pk $p)"; done >"$OUT/packets.txt"
TOT=$((WARM + N))
ge() { awk -v x="$1" -v y="$2" 'BEGIN{print (x>=y) ? "true" : "false"}'; }
lt() { awk -v x="$1" -v y="$2" 'BEGIN{print (x<y) ? "true" : "false"}'; }
o "nagle" "写 10 字节头、再写 10 字节体、再读响应（Nagle 默认开启）：往返时间中位数不小于 30ms=$(ge "${MED[0]}" 30)，小于 60ms=$(lt "${MED[0]}" 60)；${TOT} 个请求发出数据包 $(pk 9001) 个"
o "nodelay" "同样的写法，客户端设 TCP_NODELAY：中位数小于 5ms=$(lt "${MED[1]}" 5)；数据包 $(pk 9002) 个（每次写一个包）"
o "joined" "不设 TCP_NODELAY，把头和体拼成一次 sendall：中位数小于 5ms=$(lt "${MED[2]}" 5)；数据包 $(pk 9003) 个"
o "sendmsg" "不设 TCP_NODELAY，用 sendmsg([头, 体]) 一次提交两段：中位数小于 5ms=$(lt "${MED[3]}" 5)；数据包 $(pk 9004) 个"
o "sysctl" "客户端 tcp_autocorking = $(c 'cat /proc/sys/net/ipv4/tcp_autocorking')；内核 $(c 'uname -r')"
write_environment "$OUT/environment.txt" "netshoot: v0.16（固定 digest，python3 $(c 'python3 --version 2>&1 | cut -d" " -f2')）" "kernel: $(c 'uname -r')"
cat "$f" "$OUT/timings.txt" >&2
# 时间只断言范围，原始数值在 timings.txt；包数是确定的
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
nagle	写 10 字节头、再写 10 字节体、再读响应（Nagle 默认开启）：往返时间中位数不小于 30ms=true，小于 60ms=true；50 个请求发出数据包 100 个
nodelay	同样的写法，客户端设 TCP_NODELAY：中位数小于 5ms=true；数据包 100 个（每次写一个包）
joined	不设 TCP_NODELAY，把头和体拼成一次 sendall：中位数小于 5ms=true；数据包 50 个
sendmsg	不设 TCP_NODELAY，用 sendmsg([头, 体]) 一次提交两段：中位数小于 5ms=true；数据包 50 个
sysctl	客户端 tcp_autocorking = 1；内核 7.0.14-linuxkit
EXPECTED
log "全部通过，输出在 ${OUT}"
