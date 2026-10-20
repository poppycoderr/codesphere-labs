"""注意力掩码的布尔约定、因果与填充掩码、解码器以输入为 memory 时的未来泄漏：用扰动未来位置的方法检查。"""
import math
import warnings

import torch
import torch.nn as nn
import torch.nn.functional as F

warnings.filterwarnings("ignore")
torch.set_num_threads(1)
torch.manual_seed(0)
T, E, H = 5, 8, 2
TOL = 1e-6


def out(key, text):
    print(f"{key}\t{text}")


def leaks(fn, x, t):
    """扰动 t 之后的位置，看位置 0..t 的输出是否变化。"""
    y1 = fn(x)
    x2 = x.clone()
    x2[:, t + 1:] += torch.randn_like(x2[:, t + 1:])
    y2 = fn(x2)
    return bool((y1[:, : t + 1] - y2[:, : t + 1]).abs().max() > TOL)


def yn(b):
    return "会" if b else "不会"


out("env", f"torch={torch.__version__}")
x = torch.randn(1, T, E)

# 1. 同一个布尔矩阵，两个 API 含义相反
keep = torch.tril(torch.ones(T, T, dtype=torch.bool))       # True = 可以看（下三角含对角线）
block = ~keep                                                # True = 不能看（严格上三角）
q = k = v = x.view(1, T, H, E // H).transpose(1, 2)          # (B, H, T, D)
sdpa = lambda m: F.scaled_dot_product_attention(q, k, v, attn_mask=m)
ref = F.scaled_dot_product_attention(q, k, v, is_causal=True)
out("sdpa.keep", f"scaled_dot_product_attention 传下三角 True（True=参与）：与 is_causal=True 一致：{bool(torch.allclose(sdpa(keep), ref, atol=TOL))}")
out("sdpa.block", f"scaled_dot_product_attention 传严格上三角 True：与 is_causal=True 一致：{bool(torch.allclose(sdpa(block), ref, atol=TOL))}；最后一行全被遮住，这一行输出 {sdpa(block)[0, 0, -1].tolist()}")

mha = nn.MultiheadAttention(E, H, batch_first=True).eval()
run = lambda m: (lambda inp: mha(inp, inp, inp, attn_mask=m, need_weights=False)[0])
w_block = mha(x, x, x, attn_mask=block, need_weights=True, average_attn_weights=True)[1][0]
out("mha.block", f"nn.MultiheadAttention 传严格上三角 True（True=遮住）：位置 0 对位置 1..4 的权重和 {w_block[0, 1:].sum().item():.1f}；位置 2 之后的输入会影响位置 0..2：{yn(leaks(run(block), x, 2))}")
w_keep = mha(x, x, x, attn_mask=keep, need_weights=True, average_attn_weights=True)[1][0]
out("mha.keep", f"nn.MultiheadAttention 误传下三角 True：位置 0 对自己的权重 {w_keep[0, 0].item():.1f}，只看未来；位置 2 之后的输入会影响位置 0..2：{yn(leaks(run(keep), x, 2))}")
nan_w = bool(torch.isnan(mha(x, x, x, attn_mask=keep, need_weights=True)[0][0, -1]).all())
nan_f = bool(torch.isnan(mha(x, x, x, attn_mask=keep, need_weights=False)[0][0, -1]).any())
out("mha.allmasked", f"最后一行全被遮住：need_weights=True 时这一行输出全是 NaN：{nan_w}；need_weights=False 时含 NaN：{nan_f}（走了另一条计算路径，全遮住的行得到 0）")
fmask = torch.zeros(T, T).masked_fill(block, float("-inf"))
out("mha.float", f"nn.MultiheadAttention 传浮点掩码（遮住处 -inf，其余 0，按加法作用）：与布尔上三角结果一致：{bool(torch.allclose(run(fmask)(x), run(block)(x), atol=TOL))}")
try:
    mha(x, x, x, is_causal=True, need_weights=False)
    out("mha.is_causal", "nn.MultiheadAttention 只传 is_causal=True：没有报错")
except Exception as e:
    out("mha.is_causal", f"nn.MultiheadAttention 只传 is_causal=True、不传 attn_mask：{type(e).__name__}（is_causal 只是提示，掩码仍要自己给）")

# 2. 填充掩码
L = 3
real = torch.randn(1, L, E)
pad_a = torch.cat([real, torch.zeros(1, T - L, E)], dim=1)
pad_b = torch.cat([real, torch.randn(1, T - L, E) * 5], dim=1)   # 同样的真实词，填充位置的向量不同
kpm = torch.tensor([[False] * L + [True] * (T - L)])             # True = 填充，忽略
alone = mha(real, real, real, need_weights=False)[0]
no_kpm = mha(pad_a, pad_a, pad_a, need_weights=False)[0][:, :L]
no_kpm_b = mha(pad_b, pad_b, pad_b, need_weights=False)[0][:, :L]
with_kpm = mha(pad_a, pad_a, pad_a, key_padding_mask=kpm, need_weights=False)[0][:, :L]
with_kpm_b = mha(pad_b, pad_b, pad_b, key_padding_mask=kpm, need_weights=False)[0][:, :L]
out("pad.none", f"不传 key_padding_mask：真实词的输出与不填充时一致：{bool(torch.allclose(no_kpm, alone, atol=1e-5))}；随填充向量变化：{bool((no_kpm - no_kpm_b).abs().max() > TOL)}")
out("pad.kpm", f"传 key_padding_mask（True=填充）：真实词的输出与不填充时一致：{bool(torch.allclose(with_kpm, alone, atol=1e-5))}；随填充向量变化：{bool((with_kpm - with_kpm_b).abs().max() > TOL)}")
all_pad = torch.ones(1, T, dtype=torch.bool)
out("pad.all", f"一整条序列全是填充：need_weights=True 时输出含 NaN：{bool(torch.isnan(mha(pad_a, pad_a, pad_a, key_padding_mask=all_pad)[0]).any())}；need_weights=False 时：{bool(torch.isnan(mha(pad_a, pad_a, pad_a, key_padding_mask=all_pad, need_weights=False)[0]).any())}")

# 3. 解码器把输入本身当作 memory
layer = nn.TransformerDecoderLayer(E, H, dim_feedforward=16, dropout=0.0, batch_first=True)
dec = nn.TransformerDecoder(layer, num_layers=1).eval()
causal = nn.Transformer.generate_square_subsequent_mask(T)          # 浮点：上三角 -inf
out("dec.mask", f"generate_square_subsequent_mask 生成浮点掩码，位置 0 行：{[('-inf' if math.isinf(v) else '0') for v in causal[0].tolist()]}")
self_only = lambda inp: dec(inp, inp, tgt_mask=causal, tgt_is_causal=True)
both = lambda inp: dec(inp, inp, tgt_mask=causal, memory_mask=causal, tgt_is_causal=True, memory_is_causal=True)
enc_layer = nn.TransformerEncoderLayer(E, H, dim_feedforward=16, dropout=0.0, batch_first=True)
enc = nn.TransformerEncoder(enc_layer, num_layers=1, enable_nested_tensor=False).eval()
dec_only = lambda inp: enc(inp, mask=causal, is_causal=True)
out("dec.leak", f"TransformerDecoder(tgt=x, memory=x)，只给 tgt_mask：位置 2 之后的输入会影响位置 0..2：{yn(leaks(self_only, x, 2))}（交叉注意力看得到整段 memory）")
out("dec.memmask", f"同时给 memory_mask：{yn(leaks(both, x, 2))}")
out("dec.encoder", f"只用自注意力的「仅解码器」结构（TransformerEncoder + 因果掩码）：{yn(leaks(dec_only, x, 2))}")
out("dec.nomask", f"TransformerEncoder 不给因果掩码：{yn(leaks(lambda inp: enc(inp), x, 2))}")
