#!/usr/bin/env bash
# RST 从哪来：端口无人监听、接收缓冲区有未读数据时关闭、SO_LINGER 为 0、对端关闭后继续写、连接池复用被服务端关掉的空闲连接；tcpdump 标志位过滤
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
s() { docker exec "$SRV" sh -c "$1"; }
c() { docker exec "$CLI" sh -c "$1"; }
T="python3 /w/src/rst.py"
for pm in "9001 unread" "9002 read-then-close" "9003 linger0" "9004 close" "9005 keepalive" "9006 keepalive"; do
  docker exec -d "$SRV" sh -c "$T serve $pm"
done
docker exec -d "$CLI" sh -c "tcpdump -i eth0 -U -n -w /tmp/all.pcap tcp >/dev/null 2>&1"
sleep 1.5
f="$OUT/output.tsv"; : >"$f"
o() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
# 统计服务端发出的、带某个标志位的报文数
cnt() { c "tcpdump -n -r /tmp/all.pcap 'src host 172.31.10.10 and src port $1 and $2' 2>/dev/null | wc -l"; }

o "refused" "连接没有进程监听的端口 9000：$(c "$T refused 9000")"
r=$(c "$T unread 9001")
o "unread" "客户端发了 100 字节、服务端不读就 close：客户端随后读到 $r"
r2=$(c "$T unread 9002")
o "read-then-close" "对照：服务端读完再 close：客户端读到 $r2"
r3=$(c "$T linger0 9003")
o "linger0" "服务端设 SO_LINGER(on, 0) 后 close：客户端读到 ${r3}；服务端 TIME_WAIT $(s "ss -Htan state time-wait '( sport = :9003 )' | wc -l") 个"
o "writes" "服务端正常 close 之后，客户端连续写 3 次：$(c "$T writes 9004")"
IFS='|' read -r p1 p2 p3 < <(c "$T pool 9005 1.5 0")
o "pool.reuse" "连接空闲 1.5 秒（服务端空闲 1 秒就关闭）后直接复用：第一次 ${p1}，复用时${p2}，然后$p3"
IFS='|' read -r q1 q2 q3 < <(c "$T pool 9006 1.5 1")
o "pool.check" "同样的空闲连接，复用前用 MSG_PEEK 非阻塞读一个字节检查：${q2}；$q3"
sleep 0.5
c "pkill -INT tcpdump"; sleep 0.5
o "pcap.rst" "服务端发出的 RST：9001（未读就关）$(cnt 9001 'tcp[13] & 4 != 0') 个，9002（读完再关）$(cnt 9002 'tcp[13] & 4 != 0') 个，9003（linger 0）$(cnt 9003 'tcp[13] & 4 != 0') 个，9004（关闭后被写）$(cnt 9004 'tcp[13] & 4 != 0') 个，9005（池复用）$(cnt 9005 'tcp[13] & 4 != 0') 个"
o "pcap.fin" "服务端发出的 FIN：9001 $(cnt 9001 'tcp[13] & 1 != 0') 个，9002 $(cnt 9002 'tcp[13] & 1 != 0') 个，9003 $(cnt 9003 'tcp[13] & 1 != 0') 个"
o "pcap.filter" "端口 9002 上服务端发出的报文：tcp[13] & 18 != 0（SYN 或 ACK 任一置位）匹配 $(cnt 9002 'tcp[13] & 18 != 0') 个，tcp[13] & 18 == 18（SYN 与 ACK 都置位）匹配 $(cnt 9002 'tcp[13] & 18 == 18') 个，tcp[tcpflags] == tcp-syn|tcp-ack 匹配 $(cnt 9002 'tcp[tcpflags] == tcp-syn|tcp-ack') 个"
c "tcpdump -n -r /tmp/all.pcap 'host 172.31.10.10 and (port 9001 or port 9003 or port 9004 or port 9005)' 2>/dev/null" | sed -E 's/^[0-9:.]+ //' >"$OUT/tcpdump.txt"
write_environment "$OUT/environment.txt" "netshoot: v0.16（固定 digest，python3 $(c 'python3 --version 2>&1 | cut -d" " -f2')，$(c 'tcpdump --version 2>&1 | head -1')）" "kernel: $(c 'uname -r')"
cat "$f" >&2
# 错误码与报文计数是确定的，抓包明细另存
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
refused	连接没有进程监听的端口 9000：ECONNREFUSED
unread	客户端发了 100 字节、服务端不读就 close：客户端随后读到 ECONNRESET
read-then-close	对照：服务端读完再 close：客户端读到 EOF
linger0	服务端设 SO_LINGER(on, 0) 后 close：客户端读到 ECONNRESET；服务端 TIME_WAIT 0 个
writes	服务端正常 close 之后，客户端连续写 3 次：ok EPIPE EPIPE
pool.reuse	连接空闲 1.5 秒（服务端空闲 1 秒就关闭）后直接复用：第一次 PONG，复用时发送成功，然后读到 EOF
pool.check	同样的空闲连接，复用前用 MSG_PEEK 非阻塞读一个字节检查：复用前检查到对端已关闭，丢弃并新建连接；新连接得到 PONG
pcap.rst	服务端发出的 RST：9001（未读就关）1 个，9002（读完再关）0 个，9003（linger 0）1 个，9004（关闭后被写）1 个，9005（池复用）1 个
pcap.fin	服务端发出的 FIN：9001 0 个，9002 1 个，9003 0 个
pcap.filter	端口 9002 上服务端发出的报文：tcp[13] & 18 != 0（SYN 或 ACK 任一置位）匹配 4 个，tcp[13] & 18 == 18（SYN 与 ACK 都置位）匹配 1 个，tcp[tcpflags] == tcp-syn|tcp-ack 匹配 1 个
EXPECTED
log "全部通过，输出在 $OUT"
