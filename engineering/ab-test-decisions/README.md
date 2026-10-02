# A/B 实验判断的模拟

对应文章：[ab-test-decisions.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/ab-test-decisions.md)。

`src/ab_sim.py` 只用 Python 标准库（`random`、`statistics.NormalDist`），随机种子固定为 `20261002`，在固定 digest 的 Python 3.14.8 容器里运行。每个场景把「做一次实验」重复几千次，统计得出「显著」结论的比例。两组转化率用两比例 z 检验（双侧，显著性水平 0.05）。

1. 两组完全相同（A/A），固定样本量，只看一次；
2. 同样的 A/A，中途看 5、10、20 次，一显著就停；
3. 真实提升 10% → 11%：样本量公式的结果，每组 2000、10000 与公式值三种样本量下检出的比例，以及显著结果里观察到的平均提升；
4. 每组 2000 人时「不显著」的比例与置信区间宽度；置信区间的覆盖率；
5. 按用户分流、每个用户多次访问且转化倾向不同：按访问检验与按用户检验；
6. 一次实验看 20 个指标，以及 Bonferroni 校正；
7. 每天基础转化率不同时：按用户检验，与把数据压成每天一个转化率后做 t 检验。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 15 秒
make evidence
make clean
```
