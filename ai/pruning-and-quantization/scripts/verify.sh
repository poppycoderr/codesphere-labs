#!/usr/bin/env bash
# 剪枝之后文件为什么没变小：prune 参数化、remove 后的稠密张量、稀疏格式的存储、结构化剪枝后重建层与偏置、半精度与动态量化（PyTorch 2.14 CPU）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，torch CPU 版安装到缓存目录；首次约 2 分钟，之后约 20 秒
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
base	原模型 state_dict：['weight', 'bias']，保存 323 KiB
prune.keys	l1_unstructured(amount=0.5) 后第一层 state_dict：['bias', 'weight_orig', 'weight_mask']；weight 不再是参数，是前向前由 weight_orig × weight_mask 算出来的属性：True
prune.size	保存 580 KiB；weight 形状 (256, 256)，零元素占比 0.50
remove	prune.remove 后：state_dict ['bias', 'weight']，保存 323 KiB；仍是 torch.strided 稠密张量，形状 (256, 256)
sparse.0.5	零元素占比 0.5：稠密 258 KiB，COO 642 KiB（索引 torch.int64），CSR 644 KiB
sparse.0.9	零元素占比 0.9：稠密 258 KiB，COO 130 KiB（索引 torch.int64），CSR 132 KiB
struct.zero	ln_structured(dim=0, amount=0.5)：weight 形状 (256, 256)，全零的行 128 个
struct.rebuild_naive	只保留非零行、去掉下一层对应列，重建为 256→128→64：与剪枝后模型输出一致：False（被剪掉的行偏置仍在，ReLU 后非零的有 58 个）
struct.rebuild	把这些常数输出折进下一层偏置后：一致：True；保存 163 KiB（原 323 KiB）
struct.acc	剪枝改变了模型：与原模型输出的最大差 0.59，需要重新评估或微调
half	half()：保存 163 KiB；与原模型输出的最大差 0.0004
quant.deprecated	调用 quantize_dynamic 时的弃用警告：torch.ao.quantization is deprecated and will be removed in 2.10. 
quant.size	quantize_dynamic(qint8)：第一层变成 torch.ao.nn.quantized.dynamic.modules.linear.Linear，保存 85 KiB
quant.diff	与原模型输出的最大差 0.0090；输入输出仍是 torch.float32，只有权重以 int8 存储，激活在运行时按批量化
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
