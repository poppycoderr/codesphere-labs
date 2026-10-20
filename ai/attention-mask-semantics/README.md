# 注意力掩码的布尔约定与未来泄漏检验

对应文章：[attention-mask-semantics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/attention-mask-semantics.md)。

用扰动法检验掩码：把位置 t 之后的输入加上随机噪声，看位置 0..t 的输出是否变化。对比 `nn.MultiheadAttention` 与 `F.scaled_dot_product_attention` 的布尔掩码含义、浮点掩码、`is_causal` 提示、填充掩码、全遮住的行，以及 `nn.TransformerDecoder` 以输入本身为 memory 的泄漏。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里安装 torch 2.14.1 CPU 版，运行并逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
