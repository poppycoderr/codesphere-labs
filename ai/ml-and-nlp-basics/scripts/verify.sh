#!/usr/bin/env bash
# 机器学习与 NLP 基础：鸢尾花上的交叉验证、标准化、混淆矩阵与模型对比；词袋、TF-IDF 权重与一次注意力计算
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.7-slim 容器，依赖见 requirements.txt；约 30 秒（首次需要下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"
PY_IMAGE="python:3.14.7-slim@sha256:51dafde81dbdb6ebde285137a295cf18a47ca95234fe388a343719cb97305b3d"
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w -e PYTHONHASHSEED=0 "$PY_IMAGE" sh -c '
  pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt
  python src/ml.py > build/ml.tsv && python src/nlp.py > build/nlp.tsv
  python --version; pip freeze' >build/versions.txt 2>&1 || { cat build/versions.txt; fail "运行失败"; }
mv build/ml.tsv build/nlp.tsv "$OUT/"
grep -E '^(Python|scikit-learn|numpy|scipy)' build/versions.txt >"$OUT/versions.txt"
write_environment "$OUT/environment.txt" "python_image: $PY_IMAGE"
cat "$OUT/ml.tsv" "$OUT/nlp.tsv" "$OUT/versions.txt" >&2
m="$OUT/ml.tsv"; n="$OUT/nlp.tsv"
expect_line "$OUT/versions.txt" "scikit-learn==1.9.1" "scikit-learn 版本"
expect_line "$m" "best_params={'kneighborsclassifier__n_neighbors': 5}；测试集准确率 0.933" "交叉验证选出 k=5，测试集 0.933"
expect_regex "$m" "^k\.1	训练 1\.000，测试 0\.967，" "k=1：训练满分，单次测试 0.967"
expect_line "$m" "花萼宽度 × 1000、k=5：不做标准化 0.667，标准化后 0.933" "单位不一致时标准化的作用"
expect_line "$m" "k=5 测试集混淆矩阵（行为真实、列为预测，Setosa/Versicolor/Virginica）：[[10, 0, 0], [0, 10, 0], [0, 2, 8]]" "混淆矩阵：两朵 Virginica 判成 Versicolor"
expect_line "$n" "bow.vocab	发货 失败 已 延迟 成功 支付 未 物流 订单 请 重试" "词袋的列顺序"
expect_line "$n" "bow.matrix	[[0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 1], [1, 0, 1, 0, 1, 1, 0, 0, 1, 0, 0], [1, 0, 0, 1, 0, 0, 1, 1, 1, 0, 0]]" "词袋矩阵"
expect_line "$n" "tfidf.订单	0.298 0.315 0.298；idf 1.000" "每篇都有的「订单」权重最低"
expect_line "$n" "tfidf.失败	0.505 0.000 0.000；idf 1.693" "只出现一次的「失败」"
expect_line "$n" "tfidf.请	0.505 0.000 0.000；idf 1.693" "「请」和「失败」权重相同"
expect_line "$n" "attention.请	0.08 0.08 0.22 0.61（行和 1.000）" "注意力权重：「请」对「重试」0.61"
expect_line "$m" "k=1、随机种子 0—19 的测试准确率：最低 0.867，最高 1.000，平均 0.953" "换 20 种划分，k=1 的测试成绩在 0.867—1.000 之间"
log "全部通过，输出在 $OUT"
