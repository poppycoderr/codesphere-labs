#!/usr/bin/env bash
# 向量检索的几个口径：度量与排序、相似度数值的可比性、近似索引的召回、先取前 k 条再过滤、精确最近邻与相关性；全部用固定种子的合成向量
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
  python src/vec.py > build/output.tsv
  python --version; pip freeze' >build/versions.txt 2>&1 || { cat build/versions.txt; fail "运行失败"; }
mv build/output.tsv "$OUT/output.tsv"
grep -E '^(Python|numpy)' build/versions.txt >"$OUT/versions.txt"
write_environment "$OUT/environment.txt" "python_image: $PY"
f="$OUT/output.tsv"; cat "$f" "$OUT/versions.txt" >&2
expect_line "$OUT/versions.txt" "numpy==2.5.3" "numpy 版本"
# 固定种子、固定的 numpy 版本与架构，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
metric.normalized	向量归一化后：余弦与欧氏距离的前 10 条重合 100%，余弦与内积重合 100%
metric.raw	向量不归一化（长度 0.5—3）：余弦与内积的前 10 条重合 32%，余弦与欧氏距离重合 20%
metric.raw_norm	全部文档的平均长度 1.76；内积取回的前 10 条平均长度 2.72，欧氏距离取回的前 10 条平均长度 0.65
score.random_8	8 维的随机无关向量对：余弦的第 95 百分位 0.59，超过 0.3 的占 21.64%
score.random_64	64 维的随机无关向量对：余弦的第 95 百分位 0.20，超过 0.3 的占 0.75%
score.random_768	768 维的随机无关向量对：余弦的第 95 百分位 0.06，超过 0.3 的占 0.00%
score.plain	合成语料：同主题文档对的余弦中位数 0.41，不同主题 -0.02；阈值 0.7 放过的不同主题对占 0%
score.shifted	给所有向量加上同一个方向之后：同主题 0.92，不同主题 0.86；阈值 0.7 放过的不同主题对占 100%
score.centered	减去全体均值再归一化：同主题 0.41，不同主题 -0.03；阈值 0.7 放过的不同主题对占 0%
score.rank_shifted	加上共同方向前后，前 10 条结果重合 82%
ann.nprobe_1	查 1 个桶：平均扫描 1.1% 的文档，前 10 条的召回 0.856，10 条全部找对的查询占 66%
ann.nprobe_2	查 2 个桶：平均扫描 2.1% 的文档，前 10 条的召回 0.893，10 条全部找对的查询占 70%
ann.nprobe_5	查 5 个桶：平均扫描 5.2% 的文档，前 10 条的召回 0.929，10 条全部找对的查询占 76%
ann.nprobe_10	查 10 个桶：平均扫描 10.2% 的文档，前 10 条的召回 0.957，10 条全部找对的查询占 80%
ann.nprobe_20	查 20 个桶：平均扫描 20.1% 的文档，前 10 条的召回 0.982，10 条全部找对的查询占 88%
ann.nprobe_100	查 100 个桶：平均扫描 100.0% 的文档，前 10 条的召回 1.000，10 条全部找对的查询占 100%
filter.post_10	先取前 10 条再过滤：平均剩 0.6 条，一条都不剩的查询占 53%，凑够 10 条的查询占 0%
filter.post_50	先取前 50 条再过滤：平均剩 2.4 条，一条都不剩的查询占 10%，凑够 10 条的查询占 0%
filter.post_200	先取前 200 条再过滤：平均剩 8.7 条，一条都不剩的查询占 0%，凑够 10 条的查询占 57%
filter.pre	先按租户筛出候选再检索：平均 10.0 条，凑够 10 条的查询占 100%
relevance.exact	精确检索的前 10 条里与查询同主题的占 97%；前 10 条全部同主题的查询占 82%，一条同主题都没有的查询占 0%
relevance.top1	排在第 1 位的文档与查询同主题的查询占 98%；第 1 位的余弦：同主题时中位数 0.61，不同主题时中位数 0.52
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
