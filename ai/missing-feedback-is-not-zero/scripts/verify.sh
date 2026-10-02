#!/usr/bin/env bash
# 评分矩阵里的缺失值：当成 0 分与只用已观测评分，在留出评分上的误差与推荐结果
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python 3.14.8 容器，只用标准库，固定随机种子；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$PY" python src/cf_sim.py >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" "seed: 20261002"
cat "$f" >&2
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
r = {k: float(v) for k, v in re.findall(r"^rmse\.(\w+)\t.*?RMSE ([\d.]+)", t, re.M)}
pred_mean, true_mean = map(float, re.search(r"预测值平均 ([\d.]+)（测试集真实平均 ([\d.]+)）", t).groups())
pop = {k: int(v) for k, v in re.findall(r"^topn\.(\w+)\t.*?其中 (\d+)% 来自", t, re.M)}
assert r["zero_filled"] > 2 * r["global_mean"], r               # 缺失当 0：比直接猜平均分差一倍以上
assert r["masked"] < r["global_mean"] and r["masked"] < r["user_mean"], r
assert pred_mean < 1.0 < 2.5 < true_mean, (pred_mean, true_mean)  # 预测值被大量的 0 拉到 1 分以下
assert pop["zero_filled"] > 40 and pop["masked"] < 20, pop      # 缺失当 0：推荐集中在被评分次数多的物品
print(f"通过：RMSE {r}；预测均值 {pred_mean} 对真实 {true_mean}；热门占比 {pop}")
PY
log "全部通过，输出在 $OUT"
