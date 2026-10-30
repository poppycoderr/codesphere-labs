#!/usr/bin/env bash
# Redis Stream 消费组：待确认列表、不自动重投、XAUTOCLAIM 接管、迟到的确认、毒消息与死信、裁剪未确认的消息、确认不删除消息
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Redis 8.10.1 容器（固定 digest）与 python:3.14.8-slim 客户端容器；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
C=(docker compose -f compose.yaml)
trap 'rc=$?; "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; exit $rc' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
f="$OUT/output.tsv"
docker run --rm --network csl-redis-stream_default -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" \
  sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）" "python_image: $PY" "$(cat requirements.txt)"
cat "$f" >&2
# 交错由 sleep 固定、并发场景只断言次数与一致性，输出是确定的
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
pending	c1 用 XREADGROUP 读到 3 条、没有 XACK：待确认 3 条，持有者 [('c1', 3)]；XLEN = 3
no-redeliver	c1 不再出现，c2 用 '>' 读新消息：拿到 0 条（已投递未确认的消息不会自动重投）
own-pending	c1 重启后用 ID '0' 读自己的待确认列表：拿到 3 条，与之前相同=True
autoclaim	空闲超过 500ms 后 c2 用 XAUTOCLAIM 接管：3 条，现在的持有者 ['c2']，投递次数 [3, 3, 3]（首次读取、c1 重读、c2 接管各算一次）
late-ack	c2 接管之后，c1 处理完第 1 条才 XACK：返回 1（不检查当前持有者是谁）；c2 处理完再 XACK 同一条返回 0，这条消息已经被两个消费者各处理过一次
poison	一条每次都处理失败的消息：投递次数到 3 次后转入死信流并确认；待确认 0 条，死信流 1 条
trim	5 条都已投递未确认时：近似裁剪 XTRIM MAXLEN ~ 2 删除 0 条（XLEN 5），精确裁剪 MAXLEN 2 后 XLEN = 2；XAUTOCLAIM 接管 2 条，另报告 3 条已被删除（消息体没了，待确认记录随之清掉）；待确认剩 2 条
ack-keeps	XACK 之后：待确认 0 条，XLEN 仍是 1（确认只清待确认记录，消息要靠 MAXLEN、MINID 或 XDEL 删除）
env	redis 8.10.1
EXPECTED
log "全部通过，输出在 ${OUT}"
