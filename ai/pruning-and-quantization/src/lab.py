"""剪枝之后文件为什么没变小：prune 的参数化、remove 之后的稠密张量、稀疏格式的存储、结构化剪枝后重建层、半精度与动态量化。"""
import io
import warnings

import torch
import torch.nn as nn
import torch.nn.utils.prune as prune

warnings.filterwarnings("ignore")
torch.set_num_threads(1)


def out(key, text):
    print(f"{key}\t{text}")


def saved_kb(obj):
    buf = io.BytesIO()
    torch.save(obj, buf)
    return f"{buf.getbuffer().nbytes / 1024:.0f} KiB"


def model(seed=0):
    torch.manual_seed(seed)
    return nn.Sequential(nn.Linear(256, 256), nn.ReLU(), nn.Linear(256, 64)).eval()


out("env", f"torch={torch.__version__}")
torch.manual_seed(42)
x = torch.randn(32, 256)
m = model()
ref = m(x)
out("base", f"原模型 state_dict：{list(m[0].state_dict().keys())}，保存 {saved_kb(m.state_dict())}")

# 1. 非结构化剪枝：参数化，不是删除
prune.l1_unstructured(m[0], "weight", amount=0.5)
w = m[0].weight
out("prune.keys", f"l1_unstructured(amount=0.5) 后第一层 state_dict：{list(m[0].state_dict().keys())}；weight 不再是参数，是前向前由 weight_orig × weight_mask 算出来的属性：{'weight' not in dict(m[0].named_parameters())}")
out("prune.size", f"保存 {saved_kb(m.state_dict())}；weight 形状 {tuple(w.shape)}，零元素占比 {(w == 0).float().mean().item():.2f}")
prune.remove(m[0], "weight")
out("remove", f"prune.remove 后：state_dict {list(m[0].state_dict().keys())}，保存 {saved_kb(m.state_dict())}；仍是 {m[0].weight.layout} 稠密张量，形状 {tuple(m[0].weight.shape)}")

# 2. 稀疏格式的存储代价与稀疏度有关
for amount in (0.5, 0.9):
    t = model()[0].weight.detach().clone()
    k = int(t.numel() * amount)
    thr = t.abs().flatten().kthvalue(k).values
    t[t.abs() <= thr] = 0
    coo, csr = t.to_sparse(), t.to_sparse_csr()
    out(f"sparse.{amount}", f"零元素占比 {amount}：稠密 {saved_kb(t)}，COO {saved_kb(coo)}（索引 {coo.indices().dtype}），CSR {saved_kb(csr)}")

# 3. 结构化剪枝：整行置零，形状仍不变；要变小得重建这一层和下一层
m2 = model()
ref2 = m2(x)
prune.ln_structured(m2[0], "weight", amount=0.5, n=2, dim=0)
prune.remove(m2[0], "weight")
keep = (m2[0].weight.abs().sum(dim=1) > 0).nonzero().flatten()
out("struct.zero", f"ln_structured(dim=0, amount=0.5)：weight 形状 {tuple(m2[0].weight.shape)}，全零的行 {256 - len(keep)} 个")
first, second = nn.Linear(256, len(keep)), nn.Linear(len(keep), 64)
with torch.no_grad():
    first.weight.copy_(m2[0].weight[keep]); first.bias.copy_(m2[0].bias[keep])
    second.weight.copy_(m2[2].weight[:, keep]); second.bias.copy_(m2[2].bias)
small = nn.Sequential(first, nn.ReLU(), second).eval()
pruned_out = m2(x)
# 被剪掉的行权重为零，但偏置仍在：ReLU(bias) 可能非零，要在重建时把它折进下一层的偏置
dropped = torch.tensor([i for i in range(256) if i not in set(keep.tolist())])
with torch.no_grad():
    leak = torch.relu(m2[0].bias[dropped])
out("struct.rebuild_naive", f"只保留非零行、去掉下一层对应列，重建为 256→{len(keep)}→64：与剪枝后模型输出一致：{bool(torch.allclose(small(x), pruned_out, atol=1e-5))}（被剪掉的行偏置仍在，ReLU 后非零的有 {int((leak > 0).sum())} 个）")
with torch.no_grad():
    second.bias.add_(m2[2].weight[:, dropped] @ leak)
out("struct.rebuild", f"把这些常数输出折进下一层偏置后：一致：{bool(torch.allclose(small(x), pruned_out, atol=1e-5))}；保存 {saved_kb(small.state_dict())}（原 {saved_kb(model().state_dict())}）")
out("struct.acc", f"剪枝改变了模型：与原模型输出的最大差 {(pruned_out - ref2).abs().max().item():.2f}，需要重新评估或微调")

# 4. 半精度与动态量化
m3 = model()
half = model().half()
out("half", f"half()：保存 {saved_kb(half.state_dict())}；与原模型输出的最大差 {(half(x.half()).float() - m3(x)).abs().max().item():.4f}")
try:
    from torch.ao.quantization import quantize_dynamic
    eng = torch.backends.quantized.supported_engines
    torch.backends.quantized.engine = "qnnpack" if "qnnpack" in eng else eng[-1]
    with warnings.catch_warnings(record=True) as ws:
        warnings.simplefilter("always")
        q = quantize_dynamic(model(), {nn.Linear}, dtype=torch.qint8)
    dep = next((str(w.message).splitlines()[0] for w in ws if issubclass(w.category, DeprecationWarning)), "无")
    out("quant.deprecated", f"调用 quantize_dynamic 时的弃用警告：{dep}")
    qo = q(x)
    out("quant.size", f"quantize_dynamic(qint8)：第一层变成 {type(q[0]).__module__}.{type(q[0]).__name__}，保存 {saved_kb(q.state_dict())}")
    out("quant.diff", f"与原模型输出的最大差 {(qo - m3(x)).abs().max().item():.4f}；输入输出仍是 {qo.dtype}，只有权重以 int8 存储，激活在运行时按批量化")
except Exception as e:
    out("quant.size", f"quantize_dynamic 不可用：{type(e).__name__}: {str(e).splitlines()[0][:120]}")
