"""从 logits 到下一个词：温度、top-k、top-p、贪心、束搜索、对数概率与长度偏置。固定 logits 与随机种子。"""
import numpy as np

np.set_printoptions(precision=3, suppress=True)


def out(key, text):
    print(f"{key}\t{text}")


def softmax(z, t=1.0):
    if t == 0:                                   # 温度为 0 约定为贪心
        p = np.zeros_like(z)
        p[np.argmax(z)] = 1.0
        return p
    z = np.asarray(z, dtype=np.float64) / t
    z = z - z.max()                              # 减去最大值防止 exp 溢出
    e = np.exp(z)
    return e / e.sum()


def top_k(p, k):
    keep = np.argsort(-p)[:k]
    q = np.zeros_like(p)
    q[keep] = p[keep]
    return q / q.sum()


def top_p(p, thr):
    order = np.argsort(-p)
    cum = np.cumsum(p[order])
    n = int(np.searchsorted(cum, thr) + 1)       # 累计概率首次达到阈值的最小集合
    q = np.zeros_like(p)
    q[order[:n]] = p[order[:n]]
    return q / q.sum()


def fmt(p):
    return "[" + ", ".join(f"{x:.2f}" for x in p) + "]"


# 一、温度
logits = np.array([4.0, 3.0, 2.0, 1.0, 0.5, 0.0])
for t in (0, 0.5, 1.0, 2.0):
    p = softmax(logits, t)
    out(f"temp.{t}", f"温度 {t}：概率 {fmt(p)}，最大概率 {p.max():.3f}")
cold = np.random.default_rng(1).choice(6, size=1000, p=softmax(logits, 0.05))
out("temp.cold", f"温度 0.05 采样 1000 次：选中最大 logit 的次数 {int((cold == 0).sum())}（温度趋于 0 时退化为贪心）")

# 二、top-k 与 top-p：同一个阈值在尖锐和平坦的分布上
peaked = softmax(np.array([6.0, 2.0, 1.5, 1.0, 0.5, 0.0]))
flat = softmax(np.array([1.0, 0.9, 0.8, 0.7, 0.6, 0.5]))
for name, p in (("peaked", peaked), ("flat", flat)):
    out(f"filter.{name}", f"{'尖锐' if name == 'peaked' else '平坦'}分布 {fmt(p)}：top-k=3 保留 {np.count_nonzero(top_k(p, 3))} 个，top-p=0.9 保留 {np.count_nonzero(top_p(p, 0.9))} 个")
for t in (0.5, 1.5):
    out(f"order.{t}", f"先除以温度 {t} 再做 top-p=0.9：保留 {np.count_nonzero(top_p(softmax(logits, t), 0.9))} 个候选")

# 三、采样：固定种子，经验频率接近过滤后的分布
rng = np.random.default_rng(42)
q = top_k(softmax(logits, 1.0), 3)
draws = rng.choice(len(q), size=10000, p=q)
freq = np.bincount(draws, minlength=len(q)) / 10000
out("sample.topk", f"top-k=3 后的分布 {fmt(q)}；固定种子采样 10000 次的频率 {fmt(freq)}；第 4 到 6 个词出现 {int((draws >= 3).sum())} 次")
seqs = {tuple(np.random.default_rng(s).choice(6, size=5, p=q)) for s in range(5)}
out("sample.seeds", f"5 个不同的种子各采样 5 步：得到 {len(seqs)} 种不同的序列；同一个种子重复两次相同={tuple(np.random.default_rng(7).choice(6, 5, p=q)) == tuple(np.random.default_rng(7).choice(6, 5, p=q))}")

# 四、贪心与束搜索：一个玩具语言模型
V = ["<s>", "我", "想", "要", "吃", "饭", "</s>"]
T = {                                            # 上一个词 -> 下一个词的概率
    "<s>": {"我": 0.55, "想": 0.45},
    "我": {"要": 0.5, "想": 0.3, "吃": 0.2},
    "想": {"吃": 0.9, "要": 0.1},
    "要": {"吃": 0.35, "饭": 0.35, "</s>": 0.3},
    "吃": {"饭": 0.95, "</s>": 0.05},
    "饭": {"</s>": 1.0},
}


def step(prev, table=None):
    return (table or T).get(prev, {"</s>": 1.0})


def greedy(maxlen=6):
    seq, lp = ["<s>"], 0.0
    while seq[-1] != "</s>" and len(seq) <= maxlen:
        w, p = max(step(seq[-1]).items(), key=lambda kv: kv[1])
        seq.append(w)
        lp += np.log(p)
    return seq, lp


def beam(width, maxlen=6, alpha=0.0, table=None):
    beams = [(["<s>"], 0.0)]
    done = []
    for _ in range(maxlen):
        cand = []
        for seq, lp in beams:
            for w, p in step(seq[-1], table).items():
                cand.append((seq + [w], lp + np.log(p)))
        cand.sort(key=lambda c: -c[1])
        beams = []
        for seq, lp in cand:
            (done if seq[-1] == "</s>" else beams).append((seq, lp))
            if len(beams) == width:
                break
        if not beams:
            break
    score = lambda c: c[1] / ((len(c[0]) - 1) ** alpha)      # 长度归一化，alpha=0 时不归一化
    return max(done, key=score)


g, glp = greedy()
out("greedy", f"贪心：{''.join(g[1:-1])}，对数概率 {glp:.3f}（概率 {np.exp(glp):.3f}）")
for w in (2, 4):
    b, blp = beam(w)
    out(f"beam.{w}", f"束宽 {w}：{''.join(b[1:-1])}，对数概率 {blp:.3f}（概率 {np.exp(blp):.3f}）")

# 五、长度偏置
T2 = {                                           # 短回答一步到位，长回答每一步的概率都不低
    "<s>": {"好": 0.4, "我": 0.6},
    "好": {"</s>": 1.0},
    "我": {"今天": 0.8, "</s>": 0.2},
    "今天": {"去": 0.8, "</s>": 0.2},
    "去": {"吃饭": 0.8, "</s>": 0.2},
    "吃饭": {"</s>": 0.9, "了": 0.1},
}
b0 = beam(4, alpha=0.0, table=T2)
b1 = beam(4, alpha=1.0, table=T2)
out("beam.length", f"另一个转移表、束宽 4：不做长度归一化选中「{''.join(b0[0][1:-1])}」（{len(b0[0]) - 2} 个词，对数概率 {b0[1]:.3f}）；按长度归一化后选中「{''.join(b1[0][1:-1])}」（{len(b1[0]) - 2} 个词，对数概率 {b1[1]:.3f}，每步平均 {b1[1] / (len(b1[0]) - 1):.3f}）")

# 六、概率连乘下溢
ps = np.full(400, 0.1)
out("underflow", f"400 个 0.1 连乘：{np.prod(ps)}；对数相加：{np.log(ps).sum():.2f}（即 10^{np.log10(ps).sum():.0f}）；两个这样的序列连乘后都是 0、无法比较，对数可以比较")
out("env", f"numpy {np.__version__}")
