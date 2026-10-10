"""检索评估的几个口径：标注不全时对新系统的偏差、不同指标给出相反的结论、查询太少时差异不可靠、平均值盖住了整类失败。

全部用固定种子的合成数据：2000 篇文档分属 50 个主题，200 个查询各属一个主题，同主题的文档是「真正相关」的。
「检索系统」用「真实相关度加噪声」来模拟，噪声小的那个是真的更好。不涉及任何真实模型。
"""
import numpy as np

rng = np.random.default_rng(20261010)
DOCS, TOPICS, QUERIES, K = 2000, 50, 200, 10
doc_topic = rng.integers(0, TOPICS, DOCS)
q_topic = rng.integers(0, TOPICS, QUERIES)
relevant = doc_topic[None, :] == q_topic[:, None]                     # 完整的相关性标注：查询 × 文档

def system(noise, seed):
    """返回每个查询的文档排序；噪声越小越接近真实相关度"""
    r = np.random.default_rng(seed)
    score = relevant.astype(float) + r.normal(0, noise, relevant.shape)
    return np.argsort(-score, axis=1)

def recall_at_k(rank, labels, k=K):
    hits = np.take_along_axis(labels, rank[:, :k], axis=1).sum(axis=1)
    return hits / np.minimum(labels.sum(axis=1), k).clip(min=1)
def precision_at_k(rank, labels, k=K): return np.take_along_axis(labels, rank[:, :k], axis=1).mean(axis=1)
def mrr(rank, labels, k=K):
    top = np.take_along_axis(labels, rank[:, :k], axis=1)
    first = np.where(top.any(axis=1), top.argmax(axis=1) + 1, 0)
    return np.where(first > 0, 1.0 / np.maximum(first, 1), 0.0)

def out(k, v): print(f"{k}\t{v}")

out("setup", f"{DOCS} 篇文档、{TOPICS} 个主题、{QUERIES} 个查询，每个查询平均有 {relevant.sum(axis=1).mean():.0f} 篇真正相关的文档；指标都取前 {K} 条")

# 一、标注只覆盖了旧系统返回过的文档
old, new = system(0.45, 1), system(0.30, 2)
out("pool.full", f"用完整标注评估：旧系统的前 {K} 条准确率 {precision_at_k(old, relevant).mean():.3f}，新系统 {precision_at_k(new, relevant).mean():.3f}")
pooled = np.zeros_like(relevant)
np.put_along_axis(pooled, old[:, :K], np.take_along_axis(relevant, old[:, :K], axis=1), axis=1)   # 只有旧系统前 10 条被人工看过，其余一律当作不相关
out("pool.old_only", f"标注只覆盖旧系统返回过的文档（没标注的算不相关）：旧系统 {precision_at_k(old, pooled).mean():.3f}，新系统 {precision_at_k(new, pooled).mean():.3f}")
new_top = np.take_along_axis(relevant, new[:, :K], axis=1); new_top_labeled = np.take_along_axis(pooled, new[:, :K], axis=1)
out("pool.unjudged", f"新系统返回的真正相关的文档里，有 {100 * (1 - new_top_labeled.sum() / new_top.sum()):.0f}% 从来没有被标注过")

# 二、两个指标给出相反的结论
first_hit = system(0.60, 3).copy()
for q in range(QUERIES):                                                 # 系统甲：第一条总是相关的，其余位置很差
    rel = np.flatnonzero(relevant[q]); row = first_hit[q]
    best = rel[0]; row = row[row != best]; first_hit[q] = np.concatenate([[best], row])
many = system(0.32, 4).copy()
for q in range(QUERIES):                                                 # 系统乙：前两条总是不相关的，后面很好
    irr = np.flatnonzero(~relevant[q])[:2]; row = many[q]
    row = row[~np.isin(row, irr)]; many[q] = np.concatenate([irr, row])
out("metrics.mrr", f"系统甲（第一条必中，后面差）与系统乙（前两条不中，后面好）：首个命中的倒数排名 甲 {mrr(first_hit, relevant).mean():.3f}，乙 {mrr(many, relevant).mean():.3f}")
out("metrics.precision", f"同样两个系统：前 {K} 条准确率 甲 {precision_at_k(first_hit, relevant).mean():.3f}，乙 {precision_at_k(many, relevant).mean():.3f}")

# 三、查询太少
a, b = system(0.40, 5), system(0.38, 6)
pa, pb = precision_at_k(a, relevant), precision_at_k(b, relevant)
def boot(d, n=2000):
    r = np.random.default_rng(99); idx = r.integers(0, len(d), (n, len(d)))
    m = d[idx].mean(axis=1); return np.percentile(m, 2.5), np.percentile(m, 97.5), (m < 0).mean()
for n in (30, 200):
    d = (pb - pa)[:n]; lo, hi, neg = boot(d)
    out(f"sample.{n}", f"两个相近的系统，用前 {n} 个查询比较：乙比甲高 {d.mean():+.3f}，自助法 95% 区间 [{lo:+.3f}, {hi:+.3f}]，重抽样中乙反而更差的占 {100 * neg:.0f}%")

# 四、平均值与分组
hard = q_topic < 8                                                       # 8 个主题上的查询（比如某类专有名词）系统完全处理不了
seg = system(0.30, 7).copy()
for q in np.flatnonzero(hard):
    irr = np.flatnonzero(~relevant[q]); seg[q] = np.concatenate([irr[:K], seg[q][~np.isin(seg[q], irr[:K])]])
p = precision_at_k(seg, relevant)
out("segment.mean", f"全部查询的平均准确率 {p.mean():.3f}")
out("segment.split", f"其中 {hard.sum()} 个查询（占 {100 * hard.mean():.0f}%）属于系统处理不了的那一类：这一类的准确率 {p[hard].mean():.3f}，其余 {p[~hard].mean():.3f}")
out("segment.zero", f"一条相关结果都没有的查询占 {100 * (p == 0).mean():.0f}%")
