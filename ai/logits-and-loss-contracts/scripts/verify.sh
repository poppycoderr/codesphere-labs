#!/usr/bin/env bash
# logits、概率与损失函数的约定：先 sigmoid 再传 BCEWithLogitsLoss、BCELoss 截断、先 softmax 再传 CrossEntropyLoss、类别编号错一位（PyTorch 2.14 CPU）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，torch CPU 版安装到缓存目录；首次约 2 分钟，之后约 30 秒
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
bce.-10	logit=-10、标签 1：传 logits 损失 10.0000、梯度 -1.0000；先 sigmoid 再传 损失 0.6931、梯度 -0.000023
bce.0	logit=0、标签 1：传 logits 损失 0.6931、梯度 -0.5000；先 sigmoid 再传 损失 0.4741、梯度 -0.094385
bce.10	logit=10、标签 1：传 logits 损失 0.0000、梯度 -0.0000；先 sigmoid 再传 损失 0.3133、梯度 -0.000012
bce_clamp.-20	logit=-20、标签 1：sigmoid 后 2.06e-09，BCELoss 20.00；BCEWithLogitsLoss 20.00
bce_clamp.-200	logit=-200、标签 1：sigmoid 后 0，BCELoss 100.00；BCEWithLogitsLoss 200.00
ce.floor	10 类、模型完全确信且正确：传 logits 损失 0.0000；先 softmax 再传 损失 1.4612（概率在 [0,1]，再做一次 softmax 后最多 e/(e+9)）
ce.grad	模型确信但错了：传 logits 时正确类的梯度 -1.0000；先 softmax 再传 -4.87e-18
ce.train.ok	训练 1000 步，在独立测试集上（模型输出按 logits 解释）：传 logits 准确率 0.403、交叉熵 1.749、平均最高概率 0.410
ce.train.bad	训练 1000 步，在独立测试集上（模型输出按 logits 解释）：先 softmax 再传 准确率 0.386、交叉熵 2.382、平均最高概率 0.690
ce.argmax	softmax 不改变 argmax：同一组 logits 的预测类别相同：True（只看准确率不容易发现这个错误）
off.index	标签取 1..10、类别只有 0..9：遇到 10 时 IndexError: Target 10 is out of bounds.
off.silent	训练标签整体错一位但都在 0..9 内：不报错；测试集上预测与错位标签一致的比例 0.403，与真实标签 0.062
off.ignore	CrossEntropyLoss 默认 ignore_index=-100：标签 -100 的位置不计入损失
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
