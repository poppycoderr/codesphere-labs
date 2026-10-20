#!/usr/bin/env bash
# 注意力掩码的布尔约定：MultiheadAttention 与 scaled_dot_product_attention 的 True 含义相反、填充掩码、全遮住的行、解码器以输入为 memory 时的未来泄漏（PyTorch 2.14 CPU）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，torch CPU 版从 PyTorch 索引安装到缓存目录；首次约 2 分钟，之后约 20 秒
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
sdpa.keep	scaled_dot_product_attention 传下三角 True（True=参与）：与 is_causal=True 一致：True
sdpa.block	scaled_dot_product_attention 传严格上三角 True：与 is_causal=True 一致：False；最后一行全被遮住，这一行输出 [0.0, 0.0, 0.0, 0.0]
mha.block	nn.MultiheadAttention 传严格上三角 True（True=遮住）：位置 0 对位置 1..4 的权重和 0.0；位置 2 之后的输入会影响位置 0..2：不会
mha.keep	nn.MultiheadAttention 误传下三角 True：位置 0 对自己的权重 0.0，只看未来；位置 2 之后的输入会影响位置 0..2：会
mha.allmasked	最后一行全被遮住：need_weights=True 时这一行输出全是 NaN：True；need_weights=False 时含 NaN：False（走了另一条计算路径，全遮住的行得到 0）
mha.float	nn.MultiheadAttention 传浮点掩码（遮住处 -inf，其余 0，按加法作用）：与布尔上三角结果一致：True
mha.is_causal	nn.MultiheadAttention 只传 is_causal=True、不传 attn_mask：RuntimeError（is_causal 只是提示，掩码仍要自己给）
pad.none	不传 key_padding_mask：真实词的输出与不填充时一致：False；随填充向量变化：True
pad.kpm	传 key_padding_mask（True=填充）：真实词的输出与不填充时一致：True；随填充向量变化：False
pad.all	一整条序列全是填充：need_weights=True 时输出含 NaN：True；need_weights=False 时：False
dec.mask	generate_square_subsequent_mask 生成浮点掩码，位置 0 行：['0', '-inf', '-inf', '-inf', '-inf']
dec.leak	TransformerDecoder(tgt=x, memory=x)，只给 tgt_mask：位置 2 之后的输入会影响位置 0..2：会（交叉注意力看得到整段 memory）
dec.memmask	同时给 memory_mask：不会
dec.encoder	只用自注意力的「仅解码器」结构（TransformerEncoder + 因果掩码）：不会
dec.nomask	TransformerEncoder 不给因果掩码：会
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
