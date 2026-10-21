# 偏好分数的契约与长度混杂

对应文章：[logits-and-loss-contracts.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/logits-and-loss-contracts.md)。

用标准库计算成对偏好损失、sigmoid 与 softmax，再用一个只有两个人工特征（是否满足约定的检查、长度）的线性评分器，在两组 4 对的合成偏好数据上训练，比较它对一个保留样本的偏好。不涉及任何语言模型或奖励模型训练框架。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
