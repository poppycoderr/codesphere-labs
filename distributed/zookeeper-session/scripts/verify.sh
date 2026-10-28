#!/usr/bin/env bash
# ZooKeeper 会话不是 TCP 连接：短暂断连与会话过期、临时节点何时消失、close 与进程消失、锁持有者失联、一次性 watch 与持久递归 watch
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：单节点 ZooKeeper 3.9.5 容器（固定 digest，不暴露端口）与 JDK 25 客户端容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
C=(docker compose -f compose.yaml)
trap 'rc=$?; "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; exit $rc' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
CP=""; while read -r c; do CP="$CP:/cache/m2/$(basename "$(maven_jar "$c")")"; done < deps.txt
f="$OUT/output.tsv"
docker run --rm --network csl-zk-session_default -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$IMG" java -cp "${CP#:}" src/ZkSession.java >"$f" 2>"$OUT/timings.txt"
write_environment "$OUT/environment.txt" "zookeeper_image: $(sed -n 's/^ *image: //p' compose.yaml)" "jdk_image: $IMG" "client_jars: $(tr '\n' ' ' < deps.txt)"
cat "$f" >&2
# 时间相关的结论只断言范围，具体秒数记在 timings.txt
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	zookeeper 客户端 3.9.5，会话超时 6000ms
short.states	冻结 3 秒（会话超时 6 秒）：连接状态 [CONNECTED, SUSPENDED, RECONNECTED]
short.session	断连期间临时节点还在=true，重连后仍在=true，会话 ID 不变=true
long.gone	冻结后临时节点消失的时刻在会话超时附近（5 到 9 秒）=true；不是断连的瞬间=true
long.states	冻结 12 秒：连接状态 [CONNECTED, SUSPENDED, LOST, RECONNECTED]，恢复后是新的会话=true，/b 不会自动重建=true
close.vs.crash	close() 后临时节点 1 秒内消失=true；进程消失后要等到会话超时（大于 5 秒）=true
lock.lost	持有者失联：另一个客户端拿到了锁=true；此时原持有者 isAcquiredInThisProcess()=true，它收到的连接状态 [CONNECTED, SUSPENDED]
watch	连续改两次：一次性 watch 收到 1 个事件 [NodeDataChanged]（事件里没有新值），持久递归 watch 收到 2 个；之后读到的值是 v2
EXPECTED
log "全部通过，输出在 $OUT"
