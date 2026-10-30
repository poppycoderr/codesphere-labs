#!/usr/bin/env bash
# 生成结果去重：先查后写的重复生成、EXISTS 与 GET 的窗口、两个键各自过期、SET 清掉 TTL 与 KEEPTTL、空答案被缓存、三态 + 幂等键 + 租约令牌
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
docker run --rm --network csl-generation-dedupe_default -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" \
  sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）" "python_image: $PY" "$(cat requirements.txt)"
cat "$f" >&2
# 交错由 sleep 固定、并发场景只断言次数与一致性，输出是确定的
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
naive.burst	5 次几乎同时的相同请求，先 GET 再生成再 SET：生成器被调用 5 次，5 个结果相同=True
exists.get	EXISTS 返回 1，随后 GET 返回 None（中间键过期了）；直接 GET 并判断 None 只有一次往返、没有这个窗口
two-keys	状态键与结果键分开设置过期：250ms 后状态='done'、结果=None；放进同一个哈希只有一个 TTL
set.ttl	写入前 TTL 60 秒；普通 SET 回写结果后 TTL -1（-1 表示永不过期）；SET ... KEEPTTL 后 TTL 60
empty.cached	上游一次返回空答案：被缓存 60 秒，之后 3 次请求都拿到 ''，生成器只在第一次被调用（共 1 次）
sf.burst	三态 + 幂等键：同样 5 次几乎同时的请求，生成器被调用 1 次，5 个结果都是 'answer(q1)'=True
sf.ttl	完成后记录 state=done，TTL 60 秒
sf.empty	上游返回空答案：记为 failed（FAILED:empty answer），保留 2 秒；期间再请求直接得到 FAILED:empty answer（生成器仍是 1 次）；过期后重新生成得到 'answer(q2)'，生成器共调用 2 次
sf.crash	第一个调用者拿到租约后消失（CRASHED）：第二个调用者等租约过期后接手，得到 'answer(q3)'，等待在 0.3 到 1.2 秒之间=True
sf.stale	慢的持有者生成用了 1 秒，租约 500ms 先过期，第二个调用者接手并写入 'answer(q4-new)'；旧持有者回来写入被拒绝=True，最终值 'answer(q4-new)'
EXPECTED
log "全部通过，输出在 ${OUT}"
