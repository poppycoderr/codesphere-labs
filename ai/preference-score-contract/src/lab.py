"""偏好分数的契约：成对损失只约束分数差，单个分数的 sigmoid 没有概率含义；softmax 的值取决于和谁比较；
偏好数据里的风格混杂会被评分器学走。线性评分器、两个人工特征、合成数据，不涉及任何语言模型。输出 TSV（键、说明）。"""
import math


def out(key, text):
    print(f"{key}\t{text}", flush=True)


def sigmoid(x):
    return 1 / (1 + math.exp(-x))


def pair_loss(chosen, rejected):
    """成对偏好损失 -log sigmoid(r_chosen - r_rejected)。"""
    return math.log1p(math.exp(-(chosen - rejected)))


def softmax(xs):
    m = max(xs)
    e = [math.exp(x - m) for x in xs]
    return [v / sum(e) for v in e]


# 一、损失只看差值
out("shift", f"分数 (2, 1) 的成对损失 {pair_loss(2, 1):.6f}；都加 100 后 {pair_loss(102, 101):.6f}；都乘 2 后 {pair_loss(4, 2):.6f}")
out("sigmoid", f"单个分数 2 与 102 的 sigmoid：{sigmoid(2):.6f} 与 {sigmoid(102):.6f}，训练目标却认为这两组分数一样好")
out("softmax", f"同一个分数 2：与 [1] 比较时 softmax {softmax([2, 1])[0]:.6f}，与 [1, 2] 比较时 {softmax([2, 1, 2])[0]:.6f}")


# 二、混杂：特征 = [满足约定的检查, 长度]
def train(pairs, epochs=200, lr=0.1):
    w = [0.0, 0.0]
    for _ in range(epochs):
        grad = [0.0, 0.0]
        for chosen, rejected in pairs:
            delta = [c - r for c, r in zip(chosen, rejected)]
            factor = sigmoid(-sum(wi * di for wi, di in zip(w, delta)))
            for i in range(2):
                grad[i] -= factor * delta[i] / len(pairs)
        w = [wi - lr * gi for wi, gi in zip(w, grad)]
    return w


score = lambda w, x: sum(a * b for a, b in zip(w, x))
confounded = [([1, 3], [0, 1]), ([1, 4], [0, 2]), ([1, 2], [0, 1]), ([1, 5], [0, 3])]   # 被选中的总是更长
balanced = [([1, 1], [0, 3]), ([1, 4], [0, 2]), ([1, 2], [0, 5]), ([1, 5], [0, 1])]     # 长短与选择无关
heldout = ([1, 1], [0, 6])                                                             # 短而满足约定 vs 长而不满足
for name, pairs in (("confounded", confounded), ("balanced", balanced)):
    w = train(pairs)
    a, b = (score(w, x) for x in heldout)
    label = "被选中的回答总是更长" if name == "confounded" else "长短与选择无关"
    out(f"bias.{name}", f"训练数据里{label}：学到的权重（约定检查, 长度）= ({w[0]:.3f}, {w[1]:.3f})；"
                        f"保留样本「短而满足约定」得分 {a:.3f}，「长而不满足」得分 {b:.3f}，偏好 {'前者' if a > b else '后者'}")
