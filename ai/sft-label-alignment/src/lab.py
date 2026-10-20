"""微调样本的标签对齐：因果语言模型内部移位、重复移位、填充位置与 -100、只算回答部分的损失、LoRA 的初始化与梯度。"""
import warnings

import torch
import torch.nn as nn
import torch.nn.functional as F
import transformers
from transformers import GPT2Config, GPT2LMHeadModel

warnings.filterwarnings("ignore")
torch.set_num_threads(1)
transformers.logging.set_verbosity_error()
PAD, IGNORE = 0, -100


def out(key, text):
    print(f"{key}\t{text}")


def tiny(seed=0):
    torch.manual_seed(seed)
    cfg = GPT2Config(vocab_size=16, n_positions=32, n_embd=32, n_layer=1, n_head=2,
                     resid_pdrop=0.0, embd_pdrop=0.0, attn_pdrop=0.0, pad_token_id=PAD)
    return GPT2LMHeadModel(cfg)


out("env", f"torch={torch.__version__} transformers={transformers.__version__}")

# 1. labels 由模型内部移位
m = tiny().eval()
ids = torch.tensor([[1, 2, 3, 4, 5, 6]])
with torch.no_grad():
    logits = m(ids).logits
    loss_lib = m(ids, labels=ids).loss
    manual = F.cross_entropy(logits[0, :-1], ids[0, 1:])
    pre = torch.cat([ids[:, 1:], torch.tensor([[IGNORE]])], dim=1)
    loss_pre = m(ids, labels=pre).loss
    manual2 = F.cross_entropy(logits[0, :-2], ids[0, 2:])
out("shift.lib", f"labels=input_ids：损失等于「位置 t 的 logits 对位置 t+1 的词」：{bool(torch.allclose(loss_lib, manual, atol=1e-6))}")
out("shift.double", f"自己先把 labels 左移一位再传入：损失等于「位置 t 对位置 t+2」：{bool(torch.allclose(loss_pre, manual2, atol=1e-6))}")

# 训练后看效果：一条固定序列，模型学会的是下一个词还是下下个词
seq = torch.tensor([[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]])
def train(labels, steps=150):
    model = tiny(1)
    opt = torch.optim.AdamW(model.parameters(), lr=1e-2)
    model.train()
    for _ in range(steps):
        opt.zero_grad()
        model(seq, labels=labels).loss.backward()
        opt.step()
    model.eval()
    with torch.no_grad():
        return model(torch.tensor([[1, 2, 3]])).logits[0, -1].argmax().item()
good = train(seq)
bad = train(torch.cat([seq[:, 1:], torch.tensor([[IGNORE]])], dim=1))
out("shift.train", f"训练 150 步后输入 [1, 2, 3]：labels 正确时预测 {good}；重复移位时预测 {bad}")

# 2. 填充与 -100
batch = torch.tensor([[1, 2, 3, 4, 5, 6], [7, 8, 9, PAD, PAD, PAD]])
attn = (batch != PAD).long()
labels_pad = batch.clone()
labels_ign = batch.masked_fill(batch == PAD, IGNORE)
with torch.no_grad():
    lg = m(batch, attention_mask=attn).logits
    def counted(labels):
        tgt = labels[:, 1:]
        return int((tgt != IGNORE).sum())
    l_pad = m(batch, attention_mask=attn, labels=labels_pad).loss
    l_ign = m(batch, attention_mask=attn, labels=labels_ign).loss
    pad_targets = int((labels_pad[:, 1:] == PAD).sum())
out("pad.label", f"填充位置的标签仍是填充词：计入损失的位置 {counted(labels_pad)} 个，其中目标是填充词的 {pad_targets} 个；attention_mask 只影响注意力，不影响哪些位置算损失")
out("pad.ignore", f"填充位置的标签设为 -100：计入损失的位置 {counted(labels_ign)} 个；损失从 {l_pad.item():.3f} 变为 {l_ign.item():.3f}")

# 3. 只算回答部分
prompt, answer = [1, 2, 3, 4, 5, 6, 7], [8, 9]
x = torch.tensor([prompt + answer])
lab_all = x.clone()
lab_ans = torch.tensor([[IGNORE] * len(prompt) + answer])
out("asst.count", f"提示 {len(prompt)} 个词、回答 {len(answer)} 个词：labels 全保留时计入损失 {counted(lab_all)} 个位置，提示部分设 -100 后 {counted(lab_ans)} 个")
def train_ans(labels, steps=150):
    model = tiny(2)
    opt = torch.optim.AdamW(model.parameters(), lr=1e-2)
    model.train()
    for _ in range(steps):
        opt.zero_grad()
        model(x, labels=labels).loss.backward()
        opt.step()
    model.eval()
    with torch.no_grad():
        lg = model(x).logits[0]
        lp = F.log_softmax(lg, -1)
        prompt_lp = sum(lp[t, x[0, t + 1]].item() for t in range(len(prompt) - 1))
        return prompt_lp
out("asst.train", f"训练 150 步后模型对提示部分的对数概率和：全保留 {(round(train_ans(lab_all), 1) or 0.0):.1f}，只算回答 {train_ans(lab_ans):.1f}（全保留时模型也在学着复述提示）")

# 平均方式：按词平均时长样本权重更大
short = torch.tensor([[1, 2, 3]]); longer = torch.tensor([[4, 5, 6, 7, 8, 9, 10, 11, 12]])
with torch.no_grad():
    b = torch.tensor([[1, 2, 3] + [PAD] * 6, [4, 5, 6, 7, 8, 9, 10, 11, 12]])
    lb = b.masked_fill(b == PAD, IGNORE)
    token_mean = m(b, attention_mask=(b != PAD).long(), labels=lb).loss.item()
    s1 = m(short, labels=short).loss.item(); s2 = m(longer, labels=longer).loss.item()
out("asst.mean", f"同一批里 2 个词与 8 个词的目标：库按词平均 {token_mean:.3f}，等于 (2×{s1:.3f}+8×{s2:.3f})/10：{abs(token_mean - (2 * s1 + 8 * s2) / 10) < 1e-4}；按样本平均是 {(s1 + s2) / 2:.3f}")

# 4. LoRA：W + (alpha/r)·B·A
class LoRA(nn.Module):
    def __init__(self, base, r, alpha, init):
        super().__init__()
        self.base, self.scale = base, alpha / r
        for p in self.base.parameters():
            p.requires_grad_(False)
        self.A = nn.Parameter(torch.zeros(r, base.in_features))
        self.B = nn.Parameter(torch.zeros(base.out_features, r))
        if init in ("standard", "both_random"):
            nn.init.kaiming_uniform_(self.A, a=5 ** 0.5)
        if init == "both_random":
            nn.init.normal_(self.B, std=0.1)

    def forward(self, x):
        return self.base(x) + self.scale * (x @ self.A.T @ self.B.T)

torch.manual_seed(3)
base = nn.Linear(8, 4)
xin, target = torch.randn(16, 8), torch.randn(16, 4)
for init in ("standard", "both_zero", "both_random"):
    torch.manual_seed(4)
    layer = LoRA(base, r=2, alpha=4, init=init)
    same = bool(torch.allclose(layer(xin), base(xin)))
    opt = torch.optim.SGD([layer.A, layer.B], lr=0.1)
    grads = []
    for step in range(3):
        opt.zero_grad()
        F.mse_loss(layer(xin), target).backward()
        grads.append((layer.A.grad.abs().sum().item() > 0, layer.B.grad.abs().sum().item() > 0))
        opt.step()
    fmt = "，".join(f"第 {i + 1} 步 A {'有' if a else '无'}梯度、B {'有' if b else '无'}梯度" for i, (a, b) in enumerate(grads))
    out(f"lora.{init}", f"{ {'standard': 'A 随机、B 为零', 'both_zero': 'A、B 都为零', 'both_random': 'A、B 都随机'}[init]}：初始输出与原层一致：{same}；{fmt}")
