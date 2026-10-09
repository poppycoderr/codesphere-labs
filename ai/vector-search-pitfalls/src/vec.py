"""向量检索的几个口径，全部用固定种子的合成向量：
距离度量是否给出同样的排序、相似度的数值能不能跨场景比较、近似索引相对精确检索的召回、先取前 k 条再按条件过滤、精确的最近邻是否等于相关。"""
import numpy as np

rng = np.random.default_rng(20261010)
import os
D, TOPICS, N, Q, K = 64, 200, 20000, 200, 10
SIGMA = float(os.environ.get("SIGMA", "1.2"))      # 噪声向量的长度与主题方向长度之比


def out(key, text):
    print("%s\t%s" % (key, text), flush=True)


def unit(x):
    return x / np.linalg.norm(x, axis=1, keepdims=True)


def topk(scores, k):
    """每行取分数最高的 k 个下标，按分数从高到低"""
    idx = np.argpartition(-scores, k - 1, axis=1)[:, :k]
    order = np.argsort(-np.take_along_axis(scores, idx, axis=1), axis=1, kind="stable")
    return np.take_along_axis(idx, order, axis=1)


def overlap(a, b):
    return float(np.mean([len(set(x) & set(y)) / len(x) for x, y in zip(a, b)]))


# 合成语料：200 个主题，每篇文档 = 主题方向（单位长度）+ 长度约为 SIGMA 的随机噪声，再归一化；另给每篇一个 0.5 到 3 之间的长度系数
centers = unit(rng.normal(size=(TOPICS, D)))
doc_topic = rng.integers(0, TOPICS, size=N)
docs_dir = unit(centers[doc_topic] + SIGMA * rng.normal(size=(N, D)) / np.sqrt(D))
scale = rng.uniform(0.5, 3.0, size=(N, 1))
docs_raw = docs_dir * scale
q_topic = rng.integers(0, TOPICS, size=Q)
queries = unit(centers[q_topic] + SIGMA * rng.normal(size=(Q, D)) / np.sqrt(D))

# 一、度量与排序
cos = topk(queries @ docs_dir.T, K)
l2 = topk(-((queries[:, None, :] - docs_dir[None, :, :]) ** 2).sum(axis=2), K)
ip = topk(queries @ docs_dir.T, K)
out("metric.normalized", "向量归一化后：余弦与欧氏距离的前 10 条重合 %.0f%%，余弦与内积重合 %.0f%%" % (100 * overlap(cos, l2), 100 * overlap(cos, ip)))
cos_raw = topk((queries @ docs_raw.T) / np.linalg.norm(docs_raw, axis=1), K)
ip_raw = topk(queries @ docs_raw.T, K)
l2_raw = topk(-((queries[:, None, :] - docs_raw[None, :, :]) ** 2).sum(axis=2), K)
out("metric.raw", "向量不归一化（长度 0.5—3）：余弦与内积的前 10 条重合 %.0f%%，余弦与欧氏距离重合 %.0f%%" % (100 * overlap(cos_raw, ip_raw), 100 * overlap(cos_raw, l2_raw)))
out("metric.raw_norm", "全部文档的平均长度 %.2f；内积取回的前 10 条平均长度 %.2f，欧氏距离取回的前 10 条平均长度 %.2f"
    % (scale.mean(), scale[ip_raw].mean(), scale[l2_raw].mean()))

# 二、相似度的数值
for d in (8, 64, 768):
    a, b = unit(rng.normal(size=(20000, d))), unit(rng.normal(size=(20000, d)))
    c = np.sum(a * b, axis=1)
    out("score.random_%d" % d, "%d 维的随机无关向量对：余弦的第 95 百分位 %.2f，超过 0.3 的占 %.2f%%" % (d, np.percentile(c, 95), 100 * np.mean(c > 0.3)))
pairs_same, pairs_diff = [], []
for t in range(TOPICS):
    members = np.flatnonzero(doc_topic == t)
    if len(members) >= 2:
        pairs_same.append((members[0], members[1]))
    pairs_diff.append((members[0], np.flatnonzero(doc_topic == (t + 1) % TOPICS)[0]))
ps, pd = np.array(pairs_same), np.array(pairs_diff)


def pair_cos(x, pairs):
    return np.sum(x[pairs[:, 0]] * x[pairs[:, 1]], axis=1)


out("score.plain", "合成语料：同主题文档对的余弦中位数 %.2f，不同主题 %.2f；阈值 0.7 放过的不同主题对占 %.0f%%"
    % (np.median(pair_cos(docs_dir, ps)), np.median(pair_cos(docs_dir, pd)), 100 * np.mean(pair_cos(docs_dir, pd) > 0.7)))
common = unit(rng.normal(size=(1, D)))
shifted = unit(docs_dir + 2.5 * common)          # 所有向量都带一个共同的方向
out("score.shifted", "给所有向量加上同一个方向之后：同主题 %.2f，不同主题 %.2f；阈值 0.7 放过的不同主题对占 %.0f%%"
    % (np.median(pair_cos(shifted, ps)), np.median(pair_cos(shifted, pd)), 100 * np.mean(pair_cos(shifted, pd) > 0.7)))
centered = unit(shifted - shifted.mean(axis=0, keepdims=True))
out("score.centered", "减去全体均值再归一化：同主题 %.2f，不同主题 %.2f；阈值 0.7 放过的不同主题对占 %.0f%%"
    % (np.median(pair_cos(centered, ps)), np.median(pair_cos(centered, pd)), 100 * np.mean(pair_cos(centered, pd) > 0.7)))
q_shifted = unit(queries + 2.5 * common)
out("score.rank_shifted", "加上共同方向前后，前 10 条结果重合 %.0f%%" % (100 * overlap(cos, topk(q_shifted @ shifted.T, K))))

# 三、近似索引（倒排分桶）：k-means 分 100 个桶，查询时只看最近的 nprobe 个桶
NLIST = 100
cent = docs_dir[rng.choice(N, NLIST, replace=False)].copy()
for _ in range(10):
    assign = np.argmax(docs_dir @ cent.T, axis=1)
    for c in range(NLIST):
        m = docs_dir[assign == c]
        if len(m):
            cent[c] = m.mean(axis=0)
    cent = unit(cent)
assign = np.argmax(docs_dir @ cent.T, axis=1)
buckets = [np.flatnonzero(assign == c) for c in range(NLIST)]
for nprobe in (1, 2, 5, 10, 20, 100):
    probe = topk(queries @ cent.T, nprobe)
    found, scanned = [], 0
    for qi in range(Q):
        cand = np.concatenate([buckets[c] for c in probe[qi]])
        scanned += len(cand)
        s = docs_dir[cand] @ queries[qi]
        found.append(cand[np.argsort(-s, kind="stable")[:K]])
    recall = np.mean([len(set(f) & set(e)) / K for f, e in zip(found, cos)])
    full = np.mean([len(set(f) & set(e)) == K for f, e in zip(found, cos)])
    out("ann.nprobe_%d" % nprobe, "查 %d 个桶：平均扫描 %.1f%% 的文档，前 10 条的召回 %.3f，10 条全部找对的查询占 %.0f%%" % (nprobe, 100 * scanned / Q / N, recall, 100 * full))

# 四、先取前 k 条，再按条件过滤（每篇文档属于 20 个租户之一）
tenant = rng.integers(0, 20, size=N)
q_tenant = rng.integers(0, 20, size=Q)
scores = queries @ docs_dir.T
for fetch in (10, 50, 200):
    top = topk(scores, fetch)
    kept = np.array([min(K, int(np.sum(tenant[top[i]] == q_tenant[i]))) for i in range(Q)])
    out("filter.post_%d" % fetch, "先取前 %d 条再过滤：平均剩 %.1f 条，一条都不剩的查询占 %.0f%%，凑够 10 条的查询占 %.0f%%" % (fetch, kept.mean(), 100 * np.mean(kept == 0), 100 * np.mean(kept == K)))
pre = []
for i in range(Q):
    members = np.flatnonzero(tenant == q_tenant[i])
    pre.append(min(K, len(members)))
out("filter.pre", "先按租户筛出候选再检索：平均 %.1f 条，凑够 10 条的查询占 %.0f%%" % (np.mean(pre), 100 * np.mean(np.array(pre) == K)))

# 五、精确的最近邻是否等于相关（相关 = 与查询同主题）
prec = np.mean(doc_topic[cos] == q_topic[:, None])
per_q = np.mean(doc_topic[cos] == q_topic[:, None], axis=1)
out("relevance.exact", "精确检索的前 10 条里与查询同主题的占 %.0f%%；前 10 条全部同主题的查询占 %.0f%%，一条同主题都没有的查询占 %.0f%%" % (100 * prec, 100 * np.mean(per_q == 1), 100 * np.mean(per_q == 0)))
top1 = np.mean(doc_topic[cos[:, 0]] == q_topic)
out("relevance.top1", "排在第 1 位的文档与查询同主题的查询占 %.0f%%；第 1 位的余弦：同主题时中位数 %.2f，不同主题时中位数 %.2f"
    % (100 * top1, np.median(scores[np.arange(Q), cos[:, 0]][doc_topic[cos[:, 0]] == q_topic]),
       np.median(scores[np.arange(Q), cos[:, 0]][doc_topic[cos[:, 0]] != q_topic]) if np.any(doc_topic[cos[:, 0]] != q_topic) else float("nan")))
