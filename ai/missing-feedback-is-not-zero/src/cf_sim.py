"""用户—物品评分矩阵里「没评分」的两种处理：当成 0 分，与只用已观测的评分。

合成数据（固定随机种子）：每个用户、每个物品各有一个二维的潜在向量，评分 = 3 + 内积 + 噪声，裁到 1—5 分。
每个用户只观测到约 10% 的物品；物品被评分的概率不均匀（少数物品很热门）。
观测到的评分留出 20% 作为测试集，用其余的预测它们。
"""
import math
import random

USERS, ITEMS, K = 300, 120, 20
rng = random.Random(20261002)


def out(key, fact):
    print(f"{key}\t{fact}")


def generate():
    uf = [(rng.gauss(0, 1), rng.gauss(0, 1)) for _ in range(USERS)]
    vf = [(rng.gauss(0, 1), rng.gauss(0, 1)) for _ in range(ITEMS)]
    popularity = [0.03 + 0.4 * (rng.random() ** 4) for _ in range(ITEMS)]   # 多数物品冷门，少数热门
    train, test = {}, []
    for u in range(USERS):
        for i in range(ITEMS):
            if rng.random() < popularity[i]:
                r = max(1, min(5, round(3 + uf[u][0] * vf[i][0] + uf[u][1] * vf[i][1] + rng.gauss(0, 0.5))))
                if rng.random() < 0.2:
                    test.append((u, i, r))
                else:
                    train[(u, i)] = r
    return train, test


def cosine(a, b):
    na, nb = math.sqrt(sum(x * x for x in a)), math.sqrt(sum(x * x for x in b))
    return 0.0 if na == 0 or nb == 0 else sum(x * y for x, y in zip(a, b)) / (na * nb)


def predict_zero_filled(train, by_user):
    """缺失当 0：整行向量算余弦相似度，再对最相似的 K 个用户在该物品上的「评分」（含 0）加权平均。"""
    rows = [[train.get((u, i), 0) for i in range(ITEMS)] for u in range(USERS)]
    sims = {}

    def predict(u, i):
        if u not in sims:
            sims[u] = sorted(((cosine(rows[u], rows[v]), v) for v in range(USERS) if v != u), reverse=True)[:K]
        den = sum(s for s, _ in sims[u])
        return sum(s * rows[v][i] for s, v in sims[u]) / den if den else 0.0
    return predict


def predict_masked(train, by_user):
    """只用已观测的评分：相似度在共同评分的物品上、对各自均值的偏差上计算；只让评过该物品的邻居参与，再加回用户均值。"""
    mean = {u: sum(r.values()) / len(r) for u, r in by_user.items()}
    global_mean = sum(train.values()) / len(train)
    cache = {}

    def sim(u, v):
        key = (u, v) if u < v else (v, u)
        if key not in cache:
            common = by_user[u].keys() & by_user[v].keys()
            if len(common) < 3:
                cache[key] = 0.0
            else:
                a = [by_user[u][i] - mean[u] for i in common]
                b = [by_user[v][i] - mean[v] for i in common]
                cache[key] = cosine(a, b) * min(len(common), 10) / 10      # 共同评分少的相似度打折
        return cache[key]

    def predict(u, i):
        base = mean.get(u, global_mean)
        neighbors = sorted(((sim(u, v), v) for v in by_user if v != u and i in by_user[v]), reverse=True)[:K]
        neighbors = [(s, v) for s, v in neighbors if s > 0]
        den = sum(s for s, _ in neighbors)
        if not den:
            return base
        return max(1.0, min(5.0, base + sum(s * (by_user[v][i] - mean[v]) for s, v in neighbors) / den))
    return predict


def rmse(predict, test):
    return math.sqrt(sum((predict(u, i) - r) ** 2 for u, i, r in test) / len(test))


def main():
    train, test = generate()
    by_user = {}
    for (u, i), r in train.items():
        by_user.setdefault(u, {})[i] = r
    observed = len(train) + len(test)
    out("data", f"{USERS} 个用户、{ITEMS} 个物品，共 {USERS * ITEMS} 个格子，观测到评分的 {observed} 个（{observed / (USERS * ITEMS) * 100:.1f}%），其中测试集 {len(test)} 个；训练集平均分 {sum(train.values()) / len(train):.2f}")

    global_mean = sum(train.values()) / len(train)
    user_mean = {u: sum(r.values()) / len(r) for u, r in by_user.items()}
    zero, masked = predict_zero_filled(train, by_user), predict_masked(train, by_user)
    preds_zero = [zero(u, i) for u, i, _ in test]
    out("rmse.global_mean", f"全部预测为训练集平均分：RMSE {rmse(lambda u, i: global_mean, test):.2f}")
    out("rmse.user_mean", f"预测为该用户自己的平均分：RMSE {rmse(lambda u, i: user_mean.get(u, global_mean), test):.2f}")
    out("rmse.zero_filled", f"缺失当 0 的近邻加权：RMSE {rmse(zero, test):.2f}，预测值平均 {sum(preds_zero) / len(preds_zero):.2f}（测试集真实平均 {sum(r for _, _, r in test) / len(test):.2f}）")
    out("rmse.masked", f"只用已观测评分的近邻加权：RMSE {rmse(masked, test):.2f}")

    # 给每个用户推荐 5 个没评过的物品：两种做法推荐出来的是什么
    count = [0] * ITEMS
    for (_, i) in train:
        count[i] += 1
    top10_popular = set(sorted(range(ITEMS), key=lambda i: -count[i])[:10])
    truth = {}
    for u, i, r in test:
        truth.setdefault(u, {})[i] = r

    def recommend(predict, u):
        unseen = [i for i in range(ITEMS) if i not in by_user.get(u, {})]
        return sorted(unseen, key=lambda i: -predict(u, i))[:5]

    for name, predict in (("zero_filled", zero), ("masked", masked)):
        popular = total = 0
        hit_ratings = []
        for u in range(USERS):
            for i in recommend(predict, u):
                total += 1
                popular += i in top10_popular
                if i in truth.get(u, {}):
                    hit_ratings.append(truth[u][i])
        out(f"topn.{name}", f"每人推荐 5 个：其中 {popular / total * 100:.0f}% 来自被评分次数最多的 10 个物品；推荐命中测试集的 {len(hit_ratings)} 个里，用户实际打分平均 {sum(hit_ratings) / len(hit_ratings):.2f}")


if __name__ == "__main__":
    main()
