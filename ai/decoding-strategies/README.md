# 从 logits 到下一个词

对应文章：[decoding-strategies.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/decoding-strategies.md)。

`src/lab.py` 用固定的 logits 与两个手写的转移概率表（玩具语言模型）比较：不同温度下的分布；top-k 与 top-p 在尖锐、平坦分布上保留的候选数，以及温度与 top-p 的先后；固定种子的采样频率与可复现性；贪心与束搜索的结果；束搜索是否做长度归一化；概率连乘的下溢。

```bash
make verify     # 需要 Docker；约 15 秒（首次安装 NumPy 约 1 分钟）
make evidence   # 重新采集 evidence/
make clean
```
