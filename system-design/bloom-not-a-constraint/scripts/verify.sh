#!/usr/bin/env bash
# 布隆过滤器的「一定不存在」只对已加入的元素成立：快照构建、写路径不同步、重建期间，以及并发的先查再插（SQLite 与标准库）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，只用标准库；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY"
cat "$f" >&2
# 哈希确定、交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	sqlite=3.46.1
snapshot.added	过滤器由 10000 个已有邮箱构建：已加入的邮箱里被判为不存在的 0 个
snapshot.later	构建之后又写入 100 个，写路径没有更新过滤器：这 100 个已存在的邮箱，被判为「一定不存在」的 100 个
rebuild.partial	重建到一半就切换：已存在的 10100 个邮箱里，被判为不存在的 5100 个
race.no_unique	没有唯一约束的表：两个请求都查到「一定不存在」，结果：插入成功 2 个；表里同一邮箱 2 行
race.unique	有唯一约束的表：两个请求的结果：插入成功 1 个，被唯一约束拒绝（UNIQUE constraint failed: users.email） 1 个；表里同一邮箱 1 行
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
