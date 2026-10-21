"""两阶段检索：召回决定候选集合，排序只能在候选里调整顺序。用固定的合成相关性标签计算 nDCG，
比较候选集合给排序设定的上限，以及分母取完整标签还是只取候选内标签时报告出的分数。输出 TSV（键、说明）。"""
import math

# 一个合成查询的相关性等级，预先写定，与召回分数、排序分数无关
LABELS = {"a": 0, "b": 1, "c": 3, "d": 2, "e": 0}
RETRIEVAL = {"a": 0.92, "b": 0.85, "e": 0.80, "c": 0.55, "d": 0.40}    # 召回打分：c、d 排得靠后
RANKER = {"a": 0.10, "b": 0.90, "c": 0.80, "d": 0.70, "e": 0.20}       # 排序打分：b 被高估
K = 3


def out(key, text):
    print(f"{key}\t{text}", flush=True)


def dcg(ids):
    return sum((2 ** LABELS[i] - 1) / math.log2(pos + 2) for pos, i in enumerate(ids))


def ndcg(ids, pool):
    """分母是 pool 里按相关性理想排列的前 K 个。"""
    ideal = sorted(pool, key=lambda i: -LABELS[i])[:K]
    return dcg(ids[:K]) / dcg(ideal)


def recall_top(n):
    return sorted(RETRIEVAL, key=lambda i: (-RETRIEVAL[i], i))[:n]


def rank(candidates):
    return sorted(candidates, key=lambda i: (-RANKER[i], i))


relevant = {i for i, r in LABELS.items() if r > 0}
out("labels", f"相关性等级（固定）：{LABELS}；相关文档 {sorted(relevant)}")
for n in (3, 5):
    cand = recall_top(n)
    ranked = rank(cand)
    best = sorted(cand, key=lambda i: -LABELS[i])
    cover = len(set(cand) & relevant)
    out(f"recall.{n}", f"召回前 {n} 个：{cand}，覆盖相关文档 {cover}/{len(relevant)}；排序后 {ranked[:K]}")
    out(f"ndcg.{n}", f"召回 {n} 个时 nDCG@{K}：排序器 {ndcg(ranked, LABELS):.3f}，候选内最好的排序 {ndcg(best, LABELS):.3f}（上限）")
cand = recall_top(3)
ranked = rank(cand)
out("denominator", f"召回 3 个时，分母只取候选内标签：排序器 {ndcg(ranked, cand):.3f}，候选内最好的排序 {ndcg(sorted(cand, key=lambda i: -LABELS[i]), cand):.3f}；"
                   f"分母取完整标签：{ndcg(ranked, LABELS):.3f}")
