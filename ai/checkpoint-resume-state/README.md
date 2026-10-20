# 模型产物与训练续跑状态

对应文章：[model-serving.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/model-serving.md)。

一个带 dropout、AdamW 与 StepLR 的小网络训练 40 步作为基准；另一次在第 15 步保存检查点，在随机数种子不同的「新进程」里按四个层次恢复后续训 25 步，比较最终参数与学习率衰减点。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里安装 torch 2.14.1 CPU 版，运行并逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
