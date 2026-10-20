# 验证记录：logits、概率与损失函数的约定

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| logit=-10、标签 1 | 传 logits：损失 10、梯度 -1；先 sigmoid：损失 0.69、梯度 -0.000023 |
| logit=-200、标签 1 | BCELoss 100（截断）；BCEWithLogitsLoss 200 |
| 10 类、完全确信且正确 | 传 logits 损失 0；先 softmax 损失 1.4612 |
| 完全确信但错误 | 正确类梯度：传 logits -1；先 softmax -4.87e-18 |
| 训练 1000 步后的测试集 | 传 logits：准确率 0.403、交叉熵 1.749、平均最高概率 0.410；先 softmax：0.386、2.382、0.690 |
| 标签 1..10、类别 0..9 | IndexError: Target 10 is out of bounds |
| 标签整体错一位 | 不报错；预测与错位标签一致 0.403，与真实标签 0.062 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 数据是合成的高斯簇，模型是单层线性分类器；差距的大小取决于数据、学习率与训练步数，这里只说明方向。
- 先 softmax 再传的模型准确率只略低，主要差别在交叉熵和置信度：只看准确率会漏掉这个错误。
- 没有覆盖混合精度下的数值行为。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-21 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
