# 没评分不等于打了零分

对应文章：[missing-feedback-is-not-zero.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/missing-feedback-is-not-zero.md)。

`src/cf_sim.py` 只用 Python 标准库，随机种子固定为 `20261002`。合成 300 个用户、120 个物品的评分：每个用户和物品有一个二维潜在向量，评分 = 3 + 内积 + 噪声，裁到 1—5；每个物品被评分的概率不同（少数热门），总体约 10% 的格子有评分。观测到的评分留出 20% 作测试。

比较四种预测：全局平均分、用户平均分、把缺失当 0 的近邻加权（整行余弦相似度）、只用已观测评分的近邻加权（共同评分上的偏差相似度，加回用户均值）。再看两种近邻方法各自给每个用户推荐的 5 个物品里，有多少来自被评分次数最多的 10 个。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 10 秒
make evidence
make clean
```
