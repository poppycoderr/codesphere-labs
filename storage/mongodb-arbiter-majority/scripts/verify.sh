#!/usr/bin/env bash
# 仲裁节点不存数据：PSA 副本集停掉一个数据节点后 w:majority 超时、默认写关注 w:1、主节点切换后的回滚（MongoDB 8.2.12）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：三个 mongo:8.2.12 容器（固定 digest，不暴露端口，每个限 768 MB）与 python:3.14.8-slim 客户端容器；约 2 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
PROJECT=cs-mongopsa
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
f="$OUT/output.tsv"; : >"$f"
phase() {
  docker run --rm --network "${PROJECT}_default" -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" \
    sh -c "pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py $1" >>"$f" 2>"$OUT/phase$1.err" || { tail -5 "$OUT/phase$1.err" >&2; fail "阶段 $1 失败"; }
  rm -f "$OUT/phase$1.err"
}
phase 1
"${C[@]}" stop m2 >/dev/null 2>&1          # 一个数据节点停机
phase 2
"${C[@]}" stop m1 >/dev/null 2>&1          # 旧主节点也停机，只剩 arb
"${C[@]}" start m2 >/dev/null 2>&1         # 没有 b、c 的 m2 回来
phase 3
"${C[@]}" start m1 >/dev/null 2>&1         # 旧主节点重新加入
phase 4
n=$("${C[@]}" exec -T m1 sh -c 'find /data/db/rollback -name "removed.*.bson" ! -path "*oplog*" | wc -l' | tr -d ' ')
echo "rejoin.rollback	m1 的 /data/db/rollback 下按集合 UUID 存放的被回滚文档文件：$n 个" >>"$f"
{ "${C[@]}" config --images | sed "s/^/image: /"; echo "client_image: $PY"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "python_image: $PY" "pymongo: 4.19.0"
cat "$f" >&2
# 每个阶段都等到状态确定（主节点选出、从节点追上）才继续，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	mongodb=8.2.12
config	投票成员 3 个，存数据的成员 2 个，多数为 2
default_wc	getDefaultRWConcern：defaultWriteConcern 字段不存在，来源 implicit（隐式默认值由拓扑决定，下面用行为验证）
both_up	两个数据节点都在：w:majority 写入 a 成功
m2_down.primary	停掉 m2 之后，主节点仍是 m1（m1 与 arb 两票构成多数）
m2_down.majority	w:majority（wtimeout=3000）写入 b：WTimeoutError，错误码 64
m2_down.read	主节点上 readConcern local 读到 ['a', 'b']；readConcern majority 读到 ['a']
m2_down.default	用默认写关注写入 c：立即确认，没有任何错误
failover.primary	停掉 m1、启动 m2 之后，m2 与 arb 两票选出主节点 m2
failover.read	新主节点上的数据：['a']（b、c 只在 m1 上）
failover.write	在新主节点上写入 d
rejoin.state	m1 重新加入后的状态：从节点；主节点 m2
rejoin.data	m1 上的数据：['a', 'd']；m2 上的数据：['a', 'd']；b、c 都不在了
rejoin.rollback	m1 的 /data/db/rollback 下按集合 UUID 存放的被回滚文档文件：1 个
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
