"""鸢尾花上走一遍「切分 → 预处理 → 训练 → 调参 → 评估」，输出为「键<TAB>事实」。"""
import numpy as np
from sklearn.datasets import load_iris
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import confusion_matrix, precision_score, recall_score
from sklearn.model_selection import GridSearchCV, cross_val_score, train_test_split
from sklearn.neighbors import KNeighborsClassifier
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.tree import DecisionTreeClassifier


def out(key, fact):
    print(f"{key}\t{fact}")


X, y = load_iris(return_X_y=True)
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)

# 流程：Pipeline + 训练集上 5 折交叉验证选 k，测试集只用一次
grid = [1, 3, 5, 7, 9, 15, 25, 50]
search = GridSearchCV(make_pipeline(StandardScaler(), KNeighborsClassifier()),
                      {"kneighborsclassifier__n_neighbors": grid}, cv=5)
search.fit(X_train, y_train)
out("flow", f"best_params={search.best_params_}；测试集准确率 {search.score(X_test, y_test):.3f}")

# 实验一：逐个 k 的训练、测试、交叉验证准确率
for k in grid:
    m = make_pipeline(StandardScaler(), KNeighborsClassifier(n_neighbors=k)).fit(X_train, y_train)
    cv = cross_val_score(make_pipeline(StandardScaler(), KNeighborsClassifier(n_neighbors=k)), X_train, y_train, cv=5)
    out(f"k.{k}", f"训练 {m.score(X_train, y_train):.3f}，测试 {m.score(X_test, y_test):.3f}，5 折交叉验证 {cv.mean():.3f} ± {cv.std():.3f}")

scores = []
for seed in range(20):
    a, b, c, d = train_test_split(X, y, test_size=0.2, random_state=seed, stratify=y)
    scores.append(make_pipeline(StandardScaler(), KNeighborsClassifier(n_neighbors=1)).fit(a, c).score(b, d))
out("k1.seeds", f"k=1、随机种子 0—19 的测试准确率：最低 {min(scores):.3f}，最高 {max(scores):.3f}，平均 {np.mean(scores):.3f}")

# 实验二：把花萼宽度（第 2 列）放大 1000 倍
Xs_train, Xs_test = X_train.copy(), X_test.copy()
Xs_train[:, 1] *= 1000
Xs_test[:, 1] *= 1000
raw = KNeighborsClassifier(n_neighbors=5).fit(Xs_train, y_train).score(Xs_test, y_test)
scaled = make_pipeline(StandardScaler(), KNeighborsClassifier(n_neighbors=5)).fit(Xs_train, y_train).score(Xs_test, y_test)
out("scale", f"花萼宽度 × 1000、k=5：不做标准化 {raw:.3f}，标准化后 {scaled:.3f}")

# 实验三：混淆矩阵
m5 = make_pipeline(StandardScaler(), KNeighborsClassifier(n_neighbors=5)).fit(X_train, y_train)
pred = m5.predict(X_test)
out("confusion", f"k=5 测试集混淆矩阵（行为真实、列为预测，Setosa/Versicolor/Virginica）：{confusion_matrix(y_test, pred).tolist()}")
p = precision_score(y_test, pred, average=None)
r = recall_score(y_test, pred, average=None)
out("pr", f"Versicolor 精确率 {p[1]:.3f}、召回率 {r[1]:.3f}；Virginica 精确率 {p[2]:.3f}、召回率 {r[2]:.3f}")

# 实验四：全部 150 条数据上 5 折交叉验证比较模型
models = {
    "KNN（k=5，标准化）": make_pipeline(StandardScaler(), KNeighborsClassifier(n_neighbors=5)),
    "逻辑回归（标准化）": make_pipeline(StandardScaler(), LogisticRegression(max_iter=1000)),
    "决策树（不限深度）": DecisionTreeClassifier(random_state=0),
    "决策树（最大深度 2）": DecisionTreeClassifier(max_depth=2, random_state=0),
}
for name, m in models.items():
    out("model", f"{name}：{cross_val_score(m, X, y, cv=5).mean():.3f}")
