# 验证记录：剪枝、稀疏存储与量化改变了什么

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 原模型 | 保存 323 KiB |
| `l1_unstructured(amount=0.5)` | state_dict 变成 `weight_orig` 与 `weight_mask`，保存 580 KiB；零元素占比 0.50 |
| `prune.remove` | 回到 `weight`，保存 323 KiB，仍是同形状稠密张量 |
| 稀疏度 0.5 / 0.9 | 稠密 258 KiB；COO 642 / 130 KiB；CSR 644 / 132 KiB（索引为 int64） |
| `ln_structured(dim=0)` | 形状不变，128 行全零 |
| 只删零行重建 | 输出与剪枝后模型不一致：被剪神经元的偏置经 ReLU 后仍有 58 个非零 |
| 把常数输出折进下一层偏置 | 一致；保存 163 KiB |
| `half()` | 保存 163 KiB，输出最大差 0.0004 |
| `quantize_dynamic(qint8)` | 保存 85 KiB，输出最大差 0.0090；调用时给出弃用警告 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 没有测量推理速度：稠密矩阵里的零不会让乘法变快，稀疏或量化是否更快取决于硬件、算子与批大小，需要在目标环境单独测量。
- 剪枝后的精度变化只用输出差表示，没有在任务数据上评估，也没有做剪枝后的微调。
- `torch.ao.quantization` 在 2.14.1 中仍可调用但已标记弃用，警告建议迁移到 torchao；本实验没有验证 torchao。
- 量化引擎为 qnnpack（arm64 容器），其他平台使用的引擎和误差可能不同。

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
