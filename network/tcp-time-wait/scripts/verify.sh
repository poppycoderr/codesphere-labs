#!/usr/bin/env bash
# TIME_WAIT 与端口复用：谁进入 TIME_WAIT、重启时的 Address already in use、SO_REUSEADDR 与 SO_REUSEPORT、tcp_tw_recycle 已移除、临时端口按目的地址耗尽
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：两个 netshoot v0.16 容器（固定 digest，各自一个网络命名空间，不需要特权），约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
C=(docker compose -f compose.yaml)
trap '"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d >/dev/null 2>&1 || fail "容器启动失败"
SRV=$("${C[@]}" ps -q srv); CLI=$("${C[@]}" ps -q cli)
s() { docker exec "$SRV" sh -c "$1"; }
c() { docker exec "$CLI" sh -c "$1"; }
bg() { docker exec -d "$SRV" sh -c "$1"; sleep 0.5; }
T="python3 /w/src/tcp.py"
f="$OUT/output.tsv"; : >"$f"
o() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
tw() { "$@" "ss -Htan state time-wait" ; }

# 一、谁进入 TIME_WAIT
bg "$T serve 9000 active 0 1"; c "$T connect 172.31.9.10 9000 server"; sleep 0.3
o "tw.server-closes" "服务端先关闭：服务端 TIME_WAIT $(s "ss -Htan state time-wait '( sport = :9000 )' | wc -l") 个，客户端 $(c "ss -Htan state time-wait '( dport = :9000 )' | wc -l") 个"
bg "$T serve 9001 passive 0 1"; c "$T connect 172.31.9.10 9001 client"; sleep 0.3
o "tw.client-closes" "客户端先关闭：服务端 TIME_WAIT $(s "ss -Htan state time-wait '( sport = :9001 )' | wc -l") 个，客户端 $(c "ss -Htan state time-wait '( dport = :9001 )' | wc -l") 个"
secs=$(s "ss -Htano state time-wait '( sport = :9000 )'" | sed -n 's/.*timer:(timewait,\([0-9.]*\)sec.*/\1/p' | head -1)
o "tw.timer" "TIME_WAIT 的剩余时间在 50 到 60 秒之间：$(awk -v x="${secs:-0}" 'BEGIN{print (x>=50 && x<=60) ? "true" : "false"}')；tcp_fin_timeout = $(s "cat /proc/sys/net/ipv4/tcp_fin_timeout")（它管的是 FIN_WAIT_2，不是 TIME_WAIT）"
s "ss -Htano state time-wait" >"$OUT/ss-time-wait.txt"

# 二、重启：服务端主动关闭过连接，端口上留着 TIME_WAIT
for cfg in "0 0" "0 1" "1 1"; do
  set -- $cfg; p=$((9100 + $1 * 10 + $2))
  bg "$T serve $p active $1 1"; c "$T connect 172.31.9.10 $p server"; sleep 0.3
  o "restart.$1$2" "原监听 SO_REUSEADDR=$1、有 TIME_WAIT 时重新绑定（SO_REUSEADDR=$2）：$(s "$T bind $p $2")"
done

# 三、真的有人在监听时
bg "$T serve 9200 passive 1 1"
o "listen.reuseaddr" "端口 9200 正在被监听（SO_REUSEADDR=1），另一个 SO_REUSEADDR=1 的套接字绑定：$(s "$T bind 9200 1")"
c "$T connect 172.31.9.10 9200 client"
docker exec -d "$SRV" sh -c "$T reuseport 9300 40 > /tmp/rp.txt"; sleep 0.5
s "$T connect-many 172.31.9.10 9300 40"; sleep 0.5
read -r ra rb < <(s "cat /tmp/rp.txt")
o "listen.reuseport" "两个套接字都设 SO_REUSEPORT 绑定同一端口：都成功；40 个连接被内核分给两者，两边都收到连接=$([ "${ra:-0}" -gt 0 ] && [ "${rb:-0}" -gt 0 ] && echo true || echo false)，合计 $((ra + rb))"
echo "reuseport: $ra $rb" >"$OUT/reuseport.txt"

# 四、内核参数
o "sysctl" "tcp_tw_recycle 存在=$(s "test -e /proc/sys/net/ipv4/tcp_tw_recycle && echo true || echo false")；tcp_tw_reuse = $(s "cat /proc/sys/net/ipv4/tcp_tw_reuse")；客户端临时端口范围 $(c "cat /proc/sys/net/ipv4/ip_local_port_range" | tr '\t' '-')"

# 五、临时端口耗尽：按目的地址 + 端口算
bg "$T serve 9400 passive 0 20"; bg "$T serve 9401 passive 0 20"
lis=$(c "ss -Htln | awk '{split(\$4,a,\":\"); p=a[length(a)]; if (p>=40000 && p<=40009) n++} END{print n+0}'")
o "ports.listen" "客户端命名空间里有 $lis 个监听套接字的端口落在临时端口范围内（Docker 内置 DNS 绑定端口 0 时也从这个范围里取）"
read -r ok e ports < <(c "$T burst 172.31.9.10 9400 12")
o "ports.exhaust" "客户端只有 10 个临时端口，连续连 9400、每次客户端先关：成功 $ok 次，第 $((ok + 1)) 次失败 $e，成功的连接用了 $ports 个不同的本地端口"
read -r r1 r2 < <(c "$T once 172.31.9.10 9401")
if [ "$r1" = OK ]; then o "ports.other-dest" "同一时刻连另一个目的端口 9401：成功，本地端口在 40000-40009 内=$([ "$r2" -ge 40000 ] && [ "$r2" -le 40009 ] && echo true || echo false)（与连 9400 的 TIME_WAIT 共用同一批端口）"; else o "ports.other-dest" "同一时刻连另一个目的端口 9401：失败 $r1"; fi
c "ss -Htan state time-wait" >"$OUT/ss-client-time-wait.txt"
c "uname -r" | sed 's/^/kernel: /' >"$OUT/kernel.txt"
write_environment "$OUT/environment.txt" "netshoot: v0.16（固定 digest，python3 $(c 'python3 --version 2>&1 | cut -d" " -f2')）" "kernel: $(c 'uname -r')"
cat "$f" >&2
# 计数、错误码与范围判断是确定的，原始 ss 输出另存
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
tw.server-closes	服务端先关闭：服务端 TIME_WAIT 1 个，客户端 0 个
tw.client-closes	客户端先关闭：服务端 TIME_WAIT 0 个，客户端 1 个
tw.timer	TIME_WAIT 的剩余时间在 50 到 60 秒之间：true；tcp_fin_timeout = 60（它管的是 FIN_WAIT_2，不是 TIME_WAIT）
restart.00	原监听 SO_REUSEADDR=0、有 TIME_WAIT 时重新绑定（SO_REUSEADDR=0）：EADDRINUSE
restart.01	原监听 SO_REUSEADDR=0、有 TIME_WAIT 时重新绑定（SO_REUSEADDR=1）：EADDRINUSE
restart.11	原监听 SO_REUSEADDR=1、有 TIME_WAIT 时重新绑定（SO_REUSEADDR=1）：OK
listen.reuseaddr	端口 9200 正在被监听（SO_REUSEADDR=1），另一个 SO_REUSEADDR=1 的套接字绑定：EADDRINUSE
listen.reuseport	两个套接字都设 SO_REUSEPORT 绑定同一端口：都成功；40 个连接被内核分给两者，两边都收到连接=true，合计 40
sysctl	tcp_tw_recycle 存在=false；tcp_tw_reuse = 2；客户端临时端口范围 40000-40009
ports.listen	客户端命名空间里有 1 个监听套接字的端口落在临时端口范围内（Docker 内置 DNS 绑定端口 0 时也从这个范围里取）
ports.exhaust	客户端只有 10 个临时端口，连续连 9400、每次客户端先关：成功 9 次，第 10 次失败 EADDRNOTAVAIL，成功的连接用了 9 个不同的本地端口
ports.other-dest	同一时刻连另一个目的端口 9401：成功，本地端口在 40000-40009 内=true（与连 9400 的 TIME_WAIT 共用同一批端口）
EXPECTED
log "全部通过，输出在 $OUT"
