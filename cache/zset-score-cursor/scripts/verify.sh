#!/usr/bin/env bash
# 有序集合按 score 做游标分页：同分成员的顺序、排他与包含游标、(score, member) 游标、字典序二级索引、按排名分页时新增成员（Redis 8.10.1）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：单节点 Redis 8.10.1 容器（固定 digest，不暴露端口）与 python:3.14.8-slim 客户端容器；约 20 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
PROJECT=cs-zsetcursor
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
f="$OUT/output.tsv"
docker run --rm --network "${PROJECT}_default" -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
{ "${C[@]}" config --images | sed "s/^/image: /"; echo "client_image: $PY"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "python_image: $PY" "redis-py: 8.1.0"
cat "$f" >&2
# 会话之间的交错由代码顺序固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	redis=8.10.1 redis-py=8.1.0
ties.order	按 score 倒序（ZRANGE ... BYSCORE REV）：p01:90 p07:80 p06:80 p05:80 p04:80 p03:80 p02:80 p09:70 p08:70 p10:60 p13:50 p12:50 p11:50 p14:40；同分成员按成员名逆字典序排列
cursor.exclusive	游标只记 score，下一页取 (score：得到 9 个，遗漏 ['p02', 'p03', 'p04', 'p11', 'p12']，重复 0 个
cursor.inclusive	游标只记 score，下一页取 [score 并去掉第一个：得到 127 个，遗漏 ['p02', 'p03', 'p08', 'p09', 'p10', 'p11', 'p12', 'p13', 'p14']，重复 122 个；同分成员多于一页时原地打转
cursor.composite	游标记 (score, member)，同分时按成员名继续：得到 14 个，遗漏 []，重复 0 个；与全量顺序一致：True
cursor.lex	字典序二级索引（成员 = 定宽的 999-score + ':' + 原成员，ZRANGE BYLEX 的排他游标）：得到 14 个，顺序与按 score 倒序、同分按成员名正序一致：True
insert.rank	按排名分页：第 1 页 ['p01', 'p07', 'p06', 'p05']；新增一个 95 分的成员后第 2 页 ['p05', 'p04', 'p03', 'p02']，与第 1 页重复 ['p05']
insert.cursor	按 (score, member) 游标取第 2 页：['p04', 'p03', 'p02', 'p09']，与第 1 页重复 0 个
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
