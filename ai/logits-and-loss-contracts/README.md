# logits、概率与损失函数的约定

对应文章：[logits-and-loss-contracts.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/logits-and-loss-contracts.md)。

逐个检查把概率当 logits 传给损失函数的后果：`BCEWithLogitsLoss` 前多做 sigmoid、`BCELoss` 的数值截断、`CrossEntropyLoss` 前多做 softmax（单点的损失与梯度、在重叠类别数据上训练后的测试结果），以及类别编号越界与整体错位。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里安装 torch 2.14.1 CPU 版，运行并逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
