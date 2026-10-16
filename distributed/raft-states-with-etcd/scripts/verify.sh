#!/usr/bin/env bash
# etcd 3.7.2 三节点：少数派分区下的写入、线性一致读与可串行读，raft index 的落后与追平；leader 被隔离到少数派后的重新选主与旧 leader 的表现
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：三个 etcd 容器与一次性的 etcdctl 容器（不暴露端口）；约 1—2 分钟（含等待选主）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
require python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="quay.io/coreos/etcd:v3.7.2@sha256:e9afa62b1e914f02db62e5c46a7a140d8cdf026f2ed888cab4e5a142ec4d06fd"
PROJECT=csl-raft
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d >/dev/null 2>&1 || fail "容器启动失败"
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
ctl() { docker run --rm --network "${PROJECT}_client" "$IMG" etcdctl --dial-timeout=2s --command-timeout=3s "$@" 2>/dev/null; }
# 输出 "节点 是否leader term raft_index"，连不上的节点不输出
status() { ctl --endpoints="$1" endpoint status -w json | python3 -c '
import sys, json
for x in json.load(sys.stdin):
    s = x["Status"]; print(x["Endpoint"].split(":")[0], s["header"]["member_id"] == s.get("leader"), s["raftTerm"], s["raftIndex"], "|".join(s.get("errors", [])) or "-")' || true; }
leader_of() { status "$1" | awk '$2=="True"{print $1}' | head -1; }
get() { local v; v=$(ctl --endpoints="$1:2379" get k --print-value-only "${@:2}") && echo "${v:-（空）}" || echo "失败"; }
put() { ctl --endpoints="$1:2379" put k "$2" >/dev/null && echo "成功" || echo "失败"; }
wait_for() { local i; for i in $(seq 1 "$1"); do if eval "$2"; then return 0; fi; sleep 1; done; return 1; }
cut_off() { docker network disconnect "${PROJECT}_raft" "${PROJECT}-$1-1"; }
others() { local n; for n in e1 e2 e3; do if [ "$n" != "$1" ]; then echo "$n"; fi; done; }
rejoin() { docker network connect --alias "$1-peer" "${PROJECT}_raft" "${PROJECT}-$1-1"; }

ALL=e1:2379,e2:2379,e3:2379
wait_for 30 '[ -n "$(leader_of $ALL)" ]' || fail "没有选出 leader"
LEADER=$(leader_of $ALL)
out cluster "三个节点启动后选出 1 个 leader；各节点 term 相同：$(status $ALL | awk '{print $3}' | sort -u | wc -l | tr -d ' ' | sed 's/^1$/是/;s/^[0-9]*$/否/')"
put "$LEADER" v1 >/dev/null
out read.all "写入 v1 后，三个节点的线性一致读：$(for n in e1 e2 e3; do printf '%s ' "$(get $n)"; done | sed 's/ $//')"

# 一、隔离一个 follower
F=$(others "$LEADER" | head -1)
O=$(others "$LEADER" | tail -1)
cut_off "$F"
out minority.write_majority "隔离一个 follower 后，经由 leader 写入 v2：$(put $LEADER v2)"
out minority.read_linearizable "被隔离的 follower 上线性一致读：$(get $F)"
out minority.read_serializable "被隔离的 follower 上可串行读（--consistency=s）：$(get $F --consistency=s)"
out minority.write_isolated "经由被隔离的 follower 写入：$(put $F v-x)"
out majority.read_other "另一个 follower 上线性一致读：$(get $O)"
LI=$(status "$LEADER:2379" | awk '{print $4}'); FI=$(status "$F:2379" | awk '{print $4}')
out minority.raft_index "被隔离的 follower 的 raft index 落后于 leader：$([ "$FI" -lt "$LI" ] && echo 是 || echo 否)；它报告的错误：$(status "$F:2379" | cut -d' ' -f5-)"
rejoin "$F"
wait_for 20 '[ "$(get $F --consistency=s)" = v2 ]' && r=是 || r=否
out minority.rejoin "恢复连接后，被隔离的 follower 追上并在可串行读中读到 v2：$r"

# 二、把 leader 隔离到少数派
T0=$(status "$LEADER:2379" | awk '{print $3}')
cut_off "$LEADER"
MAJ=$(others "$LEADER" | sed 's/$/:2379/' | paste -sd, -)
wait_for 30 '[ -n "$(leader_of $MAJ)" ]' && r=是 || r=否
NEWL=$(leader_of $MAJ)
T1=$(status "$NEWL:2379" | awk '{print $3}')
out leader_cut.reelect "原 leader 被隔离后，另外两个节点选出新 leader：$r；term 增大：$([ "$T1" -gt "$T0" ] && echo 是 || echo 否)"
out leader_cut.write_majority "经由新 leader 写入 v3：$(put $NEWL v3)"
sleep 5
out leader_cut.old_role "隔离 5 秒后，原 leader 是否仍认为自己是 leader：$(status "$LEADER:2379" | awk '{print ($2=="True")?"是":"否"}')；它报告的错误：$(status "$LEADER:2379" | cut -d' ' -f5-)"
out leader_cut.old_write "经由原 leader 写入：$(put $LEADER v-y)"
out leader_cut.old_read_linearizable "原 leader 上线性一致读：$(get $LEADER)"
out leader_cut.old_read_serializable "原 leader 上可串行读：$(get $LEADER --consistency=s)"
rejoin "$LEADER"
wait_for 20 '[ "$(get $LEADER --consistency=s)" = v3 ]' && r=是 || r=否
out leader_cut.rejoin "恢复连接后原 leader 读到 v3：$r；当前 leader 仍是新 leader：$([ "$(leader_of $ALL)" = "$NEWL" ] && echo 是 || echo 否)"

"${C[@]}" config --images | sed 's/^/image: /' >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "etcd: 3.7.2"
cat "$f" >&2
# 节点名不固定（谁当选 leader 不确定），输出只使用角色与是否：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
cluster	三个节点启动后选出 1 个 leader；各节点 term 相同：是
read.all	写入 v1 后，三个节点的线性一致读：v1 v1 v1
minority.write_majority	隔离一个 follower 后，经由 leader 写入 v2：成功
minority.read_linearizable	被隔离的 follower 上线性一致读：失败
minority.read_serializable	被隔离的 follower 上可串行读（--consistency=s）：v1
minority.write_isolated	经由被隔离的 follower 写入：失败
majority.read_other	另一个 follower 上线性一致读：v2
minority.raft_index	被隔离的 follower 的 raft index 落后于 leader：是；它报告的错误：etcdserver: no leader
minority.rejoin	恢复连接后，被隔离的 follower 追上并在可串行读中读到 v2：是
leader_cut.reelect	原 leader 被隔离后，另外两个节点选出新 leader：是；term 增大：是
leader_cut.write_majority	经由新 leader 写入 v3：成功
leader_cut.old_role	隔离 5 秒后，原 leader 是否仍认为自己是 leader：否；它报告的错误：etcdserver: no leader
leader_cut.old_write	经由原 leader 写入：失败
leader_cut.old_read_linearizable	原 leader 上线性一致读：失败
leader_cut.old_read_serializable	原 leader 上可串行读：v2
leader_cut.rejoin	恢复连接后原 leader 读到 v3：是；当前 leader 仍是新 leader：是
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
