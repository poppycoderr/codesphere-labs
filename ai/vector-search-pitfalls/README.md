# 向量检索的几个口径

对应文章：[vector-search-pitfalls.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/vector-search-pitfalls.md)。

`src/vec.py` 在固定 digest 的 python 3.14 容器里运行，只依赖 numpy（版本锁定在 `requirements.txt`）。数据全部是固定种子的合成向量，不涉及任何模型：64 维，200 个主题，20000 篇文档，每篇文档是「主题方向加随机噪声」，200 个查询以同样方式生成。

1. 度量与排序：归一化与不归一化时，余弦、内积、欧氏距离取回的前 10 条是否相同；
2. 相似度的数值：不同维度下随机向量对的余弦分布；给所有向量加上同一个方向之后的余弦；减去均值之后；
3. 近似索引：用 k-means 把文档分成 100 个桶，查询时只看最近的若干个桶，与精确检索比较召回；
4. 先取前 k 条再按租户过滤，与先按租户筛出候选再检索；
5. 精确检索的结果里有多少与查询同主题。

固定种子、固定的 numpy 版本与架构下输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。

## 快速运行

```bash
make verify     # 需要 Docker；首次要下载 numpy，约 1 分钟
make evidence
make clean
```
