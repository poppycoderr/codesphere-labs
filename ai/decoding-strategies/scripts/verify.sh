#!/usr/bin/env bash
# 解码策略：温度、top-k、top-p、贪心、束搜索、长度归一化与对数概率（NumPy 2.5.4）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，NumPy 2.5.4 安装到缓存目录；约 15 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
mkdir -p "$LABS_CACHE/pip"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$IMG" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
temp.0	温度 0：概率 [1.00, 0.00, 0.00, 0.00, 0.00, 0.00]，最大概率 1.000
temp.0.5	温度 0.5：概率 [0.86, 0.12, 0.02, 0.00, 0.00, 0.00]，最大概率 0.864
temp.1.0	温度 1.0：概率 [0.62, 0.23, 0.08, 0.03, 0.02, 0.01]，最大概率 0.624
temp.2.0	温度 2.0：概率 [0.40, 0.24, 0.15, 0.09, 0.07, 0.05]，最大概率 0.399
temp.cold	温度 0.05 采样 1000 次：选中最大 logit 的次数 1000（温度趋于 0 时退化为贪心）
filter.peaked	尖锐分布 [0.96, 0.02, 0.01, 0.01, 0.00, 0.00]：top-k=3 保留 3 个，top-p=0.9 保留 1 个
filter.flat	平坦分布 [0.21, 0.19, 0.17, 0.16, 0.14, 0.13]：top-k=3 保留 3 个，top-p=0.9 保留 6 个
order.0.5	先除以温度 0.5 再做 top-p=0.9：保留 2 个候选
order.1.5	先除以温度 1.5 再做 top-p=0.9：保留 4 个候选
sample.topk	top-k=3 后的分布 [0.67, 0.24, 0.09, 0.00, 0.00, 0.00]；固定种子采样 10000 次的频率 [0.67, 0.24, 0.09, 0.00, 0.00, 0.00]；第 4 到 6 个词出现 0 次
sample.seeds	5 个不同的种子各采样 5 步：得到 4 种不同的序列；同一个种子重复两次相同=True
greedy	贪心：我要吃饭，对数概率 -2.392（概率 0.091）
beam.2	束宽 2：想吃饭，对数概率 -0.955（概率 0.385）
beam.4	束宽 4：想吃饭，对数概率 -0.955（概率 0.385）
beam.length	另一个转移表、束宽 4：不做长度归一化选中「好」（1 个词，对数概率 -0.916）；按长度归一化后选中「我今天去吃饭」（4 个词，对数概率 -1.286，每步平均 -0.257）
underflow	400 个 0.1 连乘：0.0；对数相加：-921.03（即 10^-400）；两个这样的序列连乘后都是 0、无法比较，对数可以比较
env	numpy 2.5.4
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
