# 微调样本的标签对齐与 LoRA 初始化

对应文章：[sft-label-alignment.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/sft-label-alignment.md)。

用由配置随机初始化的单层 GPT-2 结构（词表 16，不下载任何权重）检查标签对齐：库内部移位与重复移位、填充位置的标签、只算回答部分、按词平均，以及手写 LoRA 层在三种初始化下的梯度。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里安装 torch 2.14.1 CPU 版与 transformers 5.20.2，运行并逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
