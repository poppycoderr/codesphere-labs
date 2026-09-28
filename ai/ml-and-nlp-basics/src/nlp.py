"""词袋、TF-IDF 与一次注意力计算，输出为「键<TAB>事实」。"""
import numpy as np
from sklearn.feature_extraction.text import CountVectorizer, TfidfVectorizer


def out(key, fact):
    print(f"{key}\t{fact}")


docs = ["订单 支付 失败 请 重试",
        "支付 成功 订单 已 发货",
        "物流 延迟 订单 未 发货"]

bow = CountVectorizer(token_pattern=r"(?u)\S+")
m = bow.fit_transform(docs).toarray()
out("bow.vocab", " ".join(bow.get_feature_names_out()))
out("bow.matrix", m.tolist())

tfidf = TfidfVectorizer(token_pattern=r"(?u)\S+")
w = tfidf.fit_transform(docs).toarray()
vocab = list(tfidf.get_feature_names_out())
for word in ["订单", "支付", "发货", "失败", "请", "重试", "延迟", "物流", "未"]:
    i = vocab.index(word)
    out(f"tfidf.{word}", f"{w[0, i]:.3f} {w[1, i]:.3f} {w[2, i]:.3f}；idf {tfidf.idf_[i]:.3f}")

tokens = ["支付", "失败", "请", "重试"]
rng = np.random.default_rng(7)
d = 8
X = rng.normal(size=(len(tokens), d))
Wq, Wk, Wv = (rng.normal(size=(d, d)) / np.sqrt(d) for _ in range(3))
Q, K, V = X @ Wq, X @ Wk, X @ Wv
scores = Q @ K.T / np.sqrt(d)
weights = np.exp(scores - scores.max(axis=1, keepdims=True))
weights /= weights.sum(axis=1, keepdims=True)
for t, row in zip(tokens, weights):
    out(f"attention.{t}", " ".join(f"{x:.2f}" for x in row) + f"（行和 {row.sum():.3f}）")
