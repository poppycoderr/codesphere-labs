# 剪枝、稀疏存储与量化改变了什么

对应文章：[pruning-and-quantization.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/pruning-and-quantization.md)。

用一个 256→256→64 的两层网络比较：`torch.nn.utils.prune` 的参数化与 `remove`，不同稀疏度下稠密、COO、CSR 的保存体积，结构化剪枝后重建更小的层，以及 `half()` 与 `quantize_dynamic` 的体积和输出误差。体积用 `torch.save` 到内存缓冲区的字节数衡量。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里安装 torch 2.14.1 CPU 版，运行并逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
