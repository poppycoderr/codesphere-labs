#!/usr/bin/env bash
# 微调样本的标签对齐：内部移位与重复移位、填充位置与 -100、只算回答部分、按词平均、LoRA 初始化与梯度（PyTorch 2.14 CPU、Transformers 5.20 随机初始化小模型）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，torch CPU 版与 transformers 安装到缓存目录，模型由配置随机初始化、不下载权重；首次约 2 分钟，之后约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python src/lab.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" "torch: 2.14.1+cpu" "transformers: 5.20.2"
cat "$f" >&2
# 固定随机种子，单线程 CPU 计算，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	torch=2.14.1+cpu transformers=5.20.2
shift.lib	labels=input_ids：损失等于「位置 t 的 logits 对位置 t+1 的词」：True
shift.double	自己先把 labels 左移一位再传入：损失等于「位置 t 对位置 t+2」：True
shift.train	训练 150 步后输入 [1, 2, 3]：labels 正确时预测 4；重复移位时预测 5
pad.label	填充位置的标签仍是填充词：计入损失的位置 10 个，其中目标是填充词的 3 个；attention_mask 只影响注意力，不影响哪些位置算损失
pad.ignore	填充位置的标签设为 -100：计入损失的位置 7 个；损失从 2.726 变为 2.839
asst.count	提示 7 个词、回答 2 个词：labels 全保留时计入损失 8 个位置，提示部分设 -100 后 2 个
asst.train	训练 150 步后模型对提示部分的对数概率和：全保留 0.0，只算回答 -54.8（全保留时模型也在学着复述提示）
asst.mean	同一批里 2 个词与 8 个词的目标：库按词平均 2.830，等于 (2×2.876+8×2.819)/10：True；按样本平均是 2.847
lora.standard	A 随机、B 为零：初始输出与原层一致：True；第 1 步 A 无梯度、B 有梯度，第 2 步 A 有梯度、B 有梯度，第 3 步 A 有梯度、B 有梯度
lora.both_zero	A、B 都为零：初始输出与原层一致：True；第 1 步 A 无梯度、B 无梯度，第 2 步 A 无梯度、B 无梯度，第 3 步 A 无梯度、B 无梯度
lora.both_random	A、B 都随机：初始输出与原层一致：False；第 1 步 A 有梯度、B 有梯度，第 2 步 A 有梯度、B 有梯度，第 3 步 A 有梯度、B 有梯度
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
