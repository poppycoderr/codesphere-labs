# 召回给排序设定的上限

对应文章：[retrieval-evaluation-pitfalls.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/retrieval-evaluation-pitfalls.md)。

一个合成查询、5 个文档，相关性等级预先写定。召回与排序都用确定性的分数代替，比较召回 3 个与 5 个时排序器的 nDCG@3、候选内最好的排序能达到的上限，以及 nDCG 的分母取完整标签还是只取候选内标签。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
