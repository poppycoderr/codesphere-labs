# 检索评估的口径

对应文章：[retrieval-evaluation-pitfalls.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/retrieval-evaluation-pitfalls.md)。

`src/evalrag.py` 在固定 digest 的 python 3.14 容器里运行，numpy 版本锁定在 `requirements.txt`。全部是固定种子的合成数据：2000 篇文档分属 50 个主题，200 个查询各属一个主题，同主题的文档是「真正相关」的，所以完整的相关性标注是已知的。「检索系统」用「真实相关度加噪声后排序」来模拟，噪声小的那个是真的更好。不涉及任何真实的模型。

四组场景：标注只覆盖旧系统返回过的文档；两个指标给出相反的结论；查询数量对比较结论的影响（自助法重抽样）；平均值与分组。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```
