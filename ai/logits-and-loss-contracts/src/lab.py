"""logits、概率与损失函数的约定：先 sigmoid 再传 BCEWithLogitsLoss、先 softmax 再传 CrossEntropyLoss、BCELoss 的截断、类别编号错一位。"""
import warnings

import torch
import torch.nn as nn
import torch.nn.functional as F

warnings.filterwarnings("ignore")
torch.set_num_threads(1)


def out(key, text):
    print(f"{key}\t{text}")


def loss_grad(fn, z, y):
    z = torch.tensor(z, requires_grad=True)
    loss = fn(z, torch.tensor(y))
    loss.backward()
    return loss.item(), z.grad.item()


out("env", f"torch={torch.__version__}")

# 1. 二分类：BCEWithLogitsLoss 期望 logits
bce_logits = nn.BCEWithLogitsLoss()
for z in (-10.0, 0.0, 10.0):
    ok = loss_grad(bce_logits, z, 1.0)
    bad = loss_grad(lambda a, b: bce_logits(torch.sigmoid(a), b), z, 1.0)
    out(f"bce.{z:g}", f"logit={z:g}、标签 1：传 logits 损失 {ok[0]:.4f}、梯度 {ok[1]:.4f}；先 sigmoid 再传 损失 {bad[0]:.4f}、梯度 {bad[1]:.6f}")

# BCELoss 自己做 sigmoid：数值截断
bce = nn.BCELoss()
for z in (-20.0, -200.0):
    zz = torch.tensor([z])
    out(f"bce_clamp.{z:g}", f"logit={z:g}、标签 1：sigmoid 后 {torch.sigmoid(zz).item():.3g}，BCELoss {bce(torch.sigmoid(zz), torch.ones(1)).item():.2f}；BCEWithLogitsLoss {bce_logits(zz, torch.ones(1)).item():.2f}")

# 2. 多分类：CrossEntropyLoss 期望 logits
C = 10
perfect = torch.full((1, C), -20.0); perfect[0, 3] = 20.0
y = torch.tensor([3])
probs = perfect.softmax(-1)
out("ce.floor", f"{C} 类、模型完全确信且正确：传 logits 损失 {F.cross_entropy(perfect, y).item():.4f}；先 softmax 再传 损失 {F.cross_entropy(probs, y).item():.4f}（概率在 [0,1]，再做一次 softmax 后最多 e/(e+{C - 1})）")
wrong = torch.full((1, C), -20.0); wrong[0, 5] = 20.0
zl = wrong.clone().requires_grad_(True); F.cross_entropy(zl, y).backward()
zp = wrong.clone().requires_grad_(True); F.cross_entropy(zp.softmax(-1), y).backward()
out("ce.grad", f"模型确信但错了：传 logits 时正确类的梯度 {zl.grad[0, 3].item():.4f}；先 softmax 再传 {zp.grad[0, 3].item():.2e}")

# 训练对比：同一个线性分类器，两种传法
torch.manual_seed(0)
centers = torch.randn(C, 16) * 0.3            # 类别有重叠，准确率到不了 1
labels = torch.randint(0, C, (2000,))
feats = centers[labels] + torch.randn(2000, 16)
test_y = torch.randint(0, C, (2000,))
test_x = centers[test_y] + torch.randn(2000, 16)
def train(wrap, steps=1000):
    torch.manual_seed(1)
    clf = nn.Linear(16, C)
    opt = torch.optim.SGD(clf.parameters(), lr=0.5)
    for _ in range(steps):
        opt.zero_grad()
        F.cross_entropy(wrap(clf(feats)), labels).backward()
        opt.step()
    with torch.no_grad():
        lg = clf(test_x)
        return ((lg.argmax(-1) == test_y).float().mean().item(), F.cross_entropy(lg, test_y).item(),
                lg.softmax(-1).max(-1).values.mean().item())
for name, wrap in (("传 logits", lambda t: t), ("先 softmax 再传", lambda t: t.softmax(-1))):
    acc, ce, conf = train(wrap)
    out(f"ce.train.{'ok' if name == '传 logits' else 'bad'}", f"训练 1000 步，在独立测试集上（模型输出按 logits 解释）：{name} 准确率 {acc:.3f}、交叉熵 {ce:.3f}、平均最高概率 {conf:.3f}")
out("ce.argmax", f"softmax 不改变 argmax：同一组 logits 的预测类别相同：{bool(torch.equal(perfect.argmax(-1), probs.argmax(-1)))}（只看准确率不容易发现这个错误）")

# 3. 类别编号错一位
y1 = torch.tensor([1, 5, 10])   # 以 1 起编号
try:
    F.cross_entropy(torch.randn(3, C), y1)
    out("off.index", "标签 10（只有 0..9 类）：没有报错")
except Exception as e:
    out("off.index", f"标签取 1..{C}、类别只有 0..{C - 1}：遇到 {C} 时 {type(e).__name__}: {str(e).splitlines()[0][:60]}")
shifted = (labels + 1) % C        # 错位但都在范围内
torch.manual_seed(1)
clf = nn.Linear(16, C); opt = torch.optim.SGD(clf.parameters(), lr=0.5)
for _ in range(1000):
    opt.zero_grad(); F.cross_entropy(clf(feats), shifted).backward(); opt.step()
with torch.no_grad():
    pred = clf(test_x).argmax(-1)
out("off.silent", f"训练标签整体错一位但都在 0..{C - 1} 内：不报错；测试集上预测与错位标签一致的比例 {(pred == (test_y + 1) % C).float().mean().item():.3f}，与真实标签 {(pred == test_y).float().mean().item():.3f}")
out("off.ignore", f"CrossEntropyLoss 默认 ignore_index={nn.CrossEntropyLoss().ignore_index}：标签 -100 的位置不计入损失")
