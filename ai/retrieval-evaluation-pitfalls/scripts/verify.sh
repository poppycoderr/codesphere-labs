#!/usr/bin/env bash
# 检索评估的口径：标注不全对新系统的偏差、两个指标结论相反、查询太少时差异不可靠、平均值盖住整类失败；全部用固定种子的合成数据
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python 3.14 容器，numpy 版本锁定在 requirements.txt（首次运行下载到仓库的 .cache/pip）；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip" build; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w -e PYTHONHASHSEED=0 "$PY" sh -c '
  pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt
  python src/evalrag.py > build/output.tsv
  python --version; pip freeze' >build/versions.txt 2>&1 || { cat build/versions.txt; fail "运行失败"; }
mv build/output.tsv "$OUT/output.tsv"
grep -E '^(Python|numpy)' build/versions.txt >"$OUT/versions.txt"
write_environment "$OUT/environment.txt" "python_image: $PY"
f="$OUT/output.tsv"; cat "$f" "$OUT/versions.txt" >&2
expect_line "$OUT/versions.txt" "numpy==2.5.3" "numpy 版本"
# 固定种子、固定的 numpy 版本与架构，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
setup	2000 篇文档、50 个主题、200 个查询，每个查询平均有 40 篇真正相关的文档；指标都取前 10 条
pool.full	用完整标注评估：旧系统的前 10 条准确率 0.775，新系统 0.985
pool.old_only	标注只覆盖旧系统返回过的文档（没标注的算不相关）：旧系统 0.775，新系统 0.182
pool.unjudged	新系统返回的真正相关的文档里，有 81% 从来没有被标注过
metrics.mrr	系统甲（第一条必中，后面差）与系统乙（前两条不中，后面好）：首个命中的倒数排名 甲 1.000，乙 0.333
metrics.precision	同样两个系统：前 10 条准确率 甲 0.555，乙 0.792
sample.30	两个相近的系统，用前 30 个查询比较：乙比甲高 +0.030，自助法 95% 区间 [+0.000, +0.060]，重抽样中乙反而更差的占 2%
sample.200	两个相近的系统，用前 200 个查询比较：乙比甲高 +0.029，自助法 95% 区间 [+0.009, +0.049]，重抽样中乙反而更差的占 0%
segment.mean	全部查询的平均准确率 0.867
segment.split	其中 25 个查询（占 12%）属于系统处理不了的那一类：这一类的准确率 0.000，其余 0.991
segment.zero	一条相关结果都没有的查询占 12%
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
