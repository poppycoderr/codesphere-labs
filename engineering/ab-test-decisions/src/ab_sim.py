"""A/B 实验判断的模拟：固定随机种子，只用标准库。

每个场景重复做很多次「实验」，统计其中有多少次得出「显著」的结论。
两组转化率的比较用两比例 z 检验（双侧）。
"""
import math
import random
from statistics import NormalDist

N01 = NormalDist()
ALPHA = 0.05
Z_CRIT = N01.inv_cdf(1 - ALPHA / 2)


def p_value(c1, n1, c2, n2):
    """两比例 z 检验的双侧 p 值。"""
    pooled = (c1 + c2) / (n1 + n2)
    se = math.sqrt(pooled * (1 - pooled) * (1 / n1 + 1 / n2))
    if se == 0:
        return 1.0
    z = (c2 / n2 - c1 / n1) / se
    return 2 * (1 - N01.cdf(abs(z)))


def out(key, fact):
    print(f"{key}\t{fact}")


def pct(x):
    return f"{x * 100:.1f}%"


def aa_fixed(rng, sims=4000, n=10_000, p=0.10):
    """1. 两组完全相同（A/A），固定样本量，只在最后看一次。"""
    hits = sum(p_value(rng.binomialvariate(n, p), n, rng.binomialvariate(n, p), n) < ALPHA for _ in range(sims))
    out("aa.fixed", f"两组转化率都是 {pct(p)}，每组 {n} 人，{sims} 次实验：判为显著 {hits} 次，占 {pct(hits / sims)}")


def aa_peeking(rng, sims=4000, n=10_000, p=0.10):
    """2. 同样的 A/A，每进来一批用户就看一次，第一次显著就停。"""
    for looks in (5, 10, 20):
        step = n // looks
        hits = 0
        for _ in range(sims):
            c1 = c2 = 0
            for i in range(1, looks + 1):
                c1 += rng.binomialvariate(step, p)
                c2 += rng.binomialvariate(step, p)
                if p_value(c1, step * i, c2, step * i) < ALPHA:
                    hits += 1
                    break
        out(f"aa.peek_{looks}", f"同样的 A/A，中途看 {looks} 次、一显著就停：判为显著 {hits} 次，占 {pct(hits / sims)}")


def required_n(p1, p2, power=0.80):
    z_b = N01.inv_cdf(power)
    return math.ceil((Z_CRIT + z_b) ** 2 * (p1 * (1 - p1) + p2 * (1 - p2)) / (p2 - p1) ** 2)


def power_and_exaggeration(rng, sims=4000, p1=0.10, p2=0.11):
    """3. 真实存在 10% → 11% 的提升时，不同样本量下检出的比例；显著结果里观察到的提升有多大。"""
    need = required_n(p1, p2)
    out("power.required_n", f"基线 {pct(p1)}、想检出提升到 {pct(p2)}、功效 80%：公式算出每组需要 {need} 人")
    for n in (2000, 10_000, need):
        hits, lifts = 0, []
        for _ in range(sims):
            c1, c2 = rng.binomialvariate(n, p1), rng.binomialvariate(n, p2)
            if p_value(c1, n, c2, n) < ALPHA and c2 > c1:
                hits += 1
                lifts.append(c2 / n - c1 / n)
        mean_lift = sum(lifts) / len(lifts)
        out(f"power.n_{n}", f"每组 {n} 人：{sims} 次实验中 {pct(hits / sims)} 检出提升；这些显著结果里观察到的平均提升 {mean_lift * 100:.2f} 个百分点（真实值 1.00）")


def not_significant(rng, sims=4000, n=2000, p1=0.10, p2=0.11):
    """4. 「不显著」的实验里，95% 置信区间有多宽。"""
    misses, widths, covers = 0, [], 0
    for _ in range(sims):
        c1, c2 = rng.binomialvariate(n, p1), rng.binomialvariate(n, p2)
        r1, r2 = c1 / n, c2 / n
        se = math.sqrt(r1 * (1 - r1) / n + r2 * (1 - r2) / n)
        lo, hi = r2 - r1 - Z_CRIT * se, r2 - r1 + Z_CRIT * se
        covers += lo <= p2 - p1 <= hi
        if p_value(c1, n, c2, n) >= ALPHA:
            misses += 1
            widths.append(hi - lo)
    out("ci.not_significant", f"真实提升 1 个百分点、每组 {n} 人：{pct(misses / sims)} 的实验不显著；这些实验的 95% 置信区间平均宽 {sum(widths) / len(widths) * 100:.1f} 个百分点")
    out("ci.coverage", f"全部 {sims} 次实验中，95% 置信区间包含真实差值的占 {pct(covers / sims)}")


def unit_of_analysis(rng, sims=1500, users=2000):
    """5. 按用户分流，却把每次访问当作独立样本（A/A：两组没有差别）。"""
    def group():
        visits = conv = user_conv = 0
        for _ in range(users):
            k = 1 + int(rng.expovariate(1 / 4))                 # 每个用户的访问次数，少数用户访问很多
            p = rng.betavariate(0.5, 4.5)                        # 每个用户自己的转化倾向，平均 10%
            c = rng.binomialvariate(k, p)
            visits += k
            conv += c
            user_conv += c > 0
        return visits, conv, user_conv
    by_visit = by_user = 0
    for _ in range(sims):
        v1, c1, u1 = group()
        v2, c2, u2 = group()
        by_visit += p_value(c1, v1, c2, v2) < ALPHA
        by_user += p_value(u1, users, u2, users) < ALPHA
    out("unit.by_visit", f"按用户分流的 A/A，把每次访问当独立样本检验：{sims} 次实验判为显著 {pct(by_visit / sims)}")
    out("unit.by_user", f"同样的数据，按用户（是否转化过）检验：判为显著 {pct(by_user / sims)}")


def many_metrics(rng, sims=4000, n=10_000, p=0.10, metrics=20):
    """6. 一次实验看 20 个互不相关的指标（A/A）。"""
    any_hit = bonf_hit = 0
    for _ in range(sims):
        ps = [p_value(rng.binomialvariate(n, p), n, rng.binomialvariate(n, p), n) for _ in range(metrics)]
        any_hit += min(ps) < ALPHA
        bonf_hit += min(ps) < ALPHA / metrics
    out("metrics.any", f"A/A 实验同时看 {metrics} 个指标：至少一个显著的实验占 {pct(any_hit / sims)}（理论值 {pct(1 - (1 - ALPHA) ** metrics)}）")
    out("metrics.bonferroni", f"把显著性水平除以指标数（{ALPHA / metrics}）之后：至少一个显著的实验占 {pct(bonf_hit / sims)}")


def daily_rates(rng, sims=4000, days=10, per_day=1000, p1=0.10, p2=0.11):
    """7. 把 10 天的数据压成每天一个转化率，再做两样本 t 检验；每天的基础转化率不同（星期效应）。"""
    t_crit = 2.101                                               # 自由度 18、双侧 0.05 的 t 临界值
    by_user = by_day = 0
    for _ in range(sims):
        a, b, ca, cb = [], [], 0, 0
        for d in range(days):
            shift = (-0.03, -0.02, 0.0, 0.02, 0.03)[d % 5]       # 两组共同的按天波动
            x, y = rng.binomialvariate(per_day, p1 + shift), rng.binomialvariate(per_day, p2 + shift)
            a.append(x / per_day); b.append(y / per_day); ca += x; cb += y
        by_user += p_value(ca, days * per_day, cb, days * per_day) < ALPHA
        ma, mb = sum(a) / days, sum(b) / days
        va, vb = sum((v - ma) ** 2 for v in a) / (days - 1), sum((v - mb) ** 2 for v in b) / (days - 1)
        t = (mb - ma) / math.sqrt(va / days + vb / days)
        by_day += abs(t) > t_crit
    out("daily.by_user", f"真实提升 1 个百分点、每组每天 {per_day} 人、{days} 天，按用户做两比例检验：{pct(by_user / sims)} 检出")
    out("daily.by_day", f"同样的数据压成每组 {days} 个日转化率再做 t 检验：{pct(by_day / sims)} 检出")


if __name__ == "__main__":
    rng = random.Random(20261002)
    aa_fixed(rng)
    aa_peeking(rng)
    power_and_exaggeration(rng)
    not_significant(rng)
    unit_of_analysis(rng)
    many_metrics(rng)
    daily_rates(rng)
