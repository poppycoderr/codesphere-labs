#!/usr/bin/env bash
# 模型产物与训练续跑状态：只恢复权重、加优化器、加调度器、加随机数状态时续训结果与不间断训练的差（PyTorch 2.14 CPU）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，torch CPU 版安装到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" "torch: 2.14.1+cpu"
cat "$f" >&2
# 固定随机种子，单线程 CPU 计算，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	torch=2.14.1+cpu
size	推理产物（只有 model.state_dict）7 KiB；完整检查点 33 KiB（AdamW 为每个参数保存两份动量）
resume.1	只恢复权重：续训第一步的学习率 0.01（中断时为 0.005），学习率在第 [25, 35] 步减半（不间断训练是 [20, 30]）；续训 25 步后与不间断训练的参数 最大差 0.1470
resume.2	权重 + 优化器：续训第一步的学习率 0.005（中断时为 0.005），学习率在第 [25, 35] 步减半（不间断训练是 [20, 30]）；续训 25 步后与不间断训练的参数 最大差 0.0392
resume.3	权重 + 优化器 + 调度器：续训第一步的学习率 0.005（中断时为 0.005），学习率在第 [20, 30] 步减半（不间断训练是 [20, 30]）；续训 25 步后与不间断训练的参数 最大差 0.0336
resume.4	再加随机数状态：续训第一步的学习率 0.005（中断时为 0.005），学习率在第 [20, 30] 步减半（不间断训练是 [20, 30]）；续训 25 步后与不间断训练的参数 逐位一致
weights_only	检查点里有自定义类的对象：torch.load 默认参数（weights_only=True）拒绝加载：UnpicklingError
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
