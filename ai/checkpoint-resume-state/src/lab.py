"""模型产物与训练续跑状态：中途停下再继续，只存权重、加上优化器、再加上调度器与随机数状态，结果与不间断训练是否一致。"""
import io
import random
import warnings

import torch
import torch.nn as nn
import torch.nn.functional as F

warnings.filterwarnings("ignore")
torch.set_num_threads(1)
STEPS, STOP = 40, 15   # 中断点不在调度器的衰减边界上


def out(k, v):
    print(f"{k}\t{v}")


torch.manual_seed(0)
X = torch.randn(256, 16)
Y = (X @ torch.randn(16, 1) + 0.1 * torch.randn(256, 1))


def build():
    m = nn.Sequential(nn.Linear(16, 64), nn.ReLU(), nn.Dropout(0.2), nn.Linear(64, 1))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-2)
    sch = torch.optim.lr_scheduler.StepLR(opt, step_size=10, gamma=0.5)
    return m, opt, sch


def step(m, opt, sch, gen):
    idx = torch.randint(0, 256, (32,), generator=gen)        # 抽样批次的随机数
    m.train()
    opt.zero_grad()
    loss = F.mse_loss(m(X[idx]), Y[idx])                      # dropout 使用全局随机数
    loss.backward()
    opt.step()
    sch.step()
    return loss.item()


def fresh(seed=1):
    torch.manual_seed(seed)
    m, opt, sch = build()
    gen = torch.Generator().manual_seed(seed)
    return m, opt, sch, gen


def flat(m):
    return torch.cat([p.detach().flatten() for p in m.parameters()])


def size(obj):
    b = io.BytesIO(); torch.save(obj, b); return f"{b.getbuffer().nbytes / 1024:.0f} KiB"


out("env", f"torch={torch.__version__}")

# 基准：不间断训练 40 步
m, opt, sch, gen = fresh()
for _ in range(STEPS):
    step(m, opt, sch, gen)
ref = flat(m)

# 训练到第 20 步，保存
m, opt, sch, gen = fresh()
for _ in range(STOP):
    step(m, opt, sch, gen)
ckpt = {
    "model": m.state_dict(), "optimizer": opt.state_dict(), "scheduler": sch.state_dict(),
    "torch_rng": torch.get_rng_state(), "batch_rng": gen.get_state(), "py_rng": random.getstate(), "step": STOP,
}
buf = io.BytesIO(); torch.save(ckpt, buf)
out("size", f"推理产物（只有 model.state_dict）{size(m.state_dict())}；完整检查点 {size(ckpt)}（AdamW 为每个参数保存两份动量）")
lr_at_stop = opt.param_groups[0]["lr"]


def resume(level):
    buf.seek(0)
    c = torch.load(buf, weights_only=True)
    torch.manual_seed(999)                   # 新进程的随机数状态和原来不同
    m2, opt2, sch2 = build()
    gen2 = torch.Generator().manual_seed(999)
    m2.load_state_dict(c["model"])
    if level >= 2:
        opt2.load_state_dict(c["optimizer"])
    if level >= 3:
        sch2.load_state_dict(c["scheduler"])
    if level >= 4:
        torch.set_rng_state(c["torch_rng"]); gen2.set_state(c["batch_rng"]); random.setstate(c["py_rng"])
    lr0 = opt2.param_groups[0]["lr"]
    lrs = []
    for _ in range(STEPS - STOP):
        lrs.append(opt2.param_groups[0]["lr"])
        step(m2, opt2, sch2, gen2)
    decays = [STOP + i for i in range(1, len(lrs)) if lrs[i] < lrs[i - 1]]
    d = (flat(m2) - ref).abs().max().item()
    return lr0, d, decays


names = {1: "只恢复权重", 2: "权重 + 优化器", 3: "权重 + 优化器 + 调度器", 4: "再加随机数状态"}
for lvl in (1, 2, 3, 4):
    lr0, d, decays = resume(lvl)
    same = "逐位一致" if d == 0 else f"最大差 {d:.4f}"
    out(f"resume.{lvl}", f"{names[lvl]}：续训第一步的学习率 {lr0:g}（中断时为 {lr_at_stop:g}），学习率在第 {decays} 步减半（不间断训练是 [20, 30]）；续训 {STEPS - STOP} 步后与不间断训练的参数 {same}")

# weights_only=True（torch 2.6 起的默认值）对任意 Python 对象的限制
class Meta:
    def __init__(self):
        self.note = "自定义对象"
b2 = io.BytesIO(); torch.save({"model": m.state_dict(), "meta": Meta()}, b2); b2.seek(0)
try:
    torch.load(b2)
    out("weights_only", "检查点里有自定义类的对象：默认参数加载成功")
except Exception as e:
    out("weights_only", f"检查点里有自定义类的对象：torch.load 默认参数（weights_only=True）拒绝加载：{type(e).__name__}")
