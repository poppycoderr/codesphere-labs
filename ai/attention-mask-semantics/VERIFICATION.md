# 验证记录：注意力掩码的布尔约定与未来泄漏检验

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `scaled_dot_product_attention` 传下三角 True | 与 `is_causal=True` 一致（True 表示参与） |
| `nn.MultiheadAttention` 传严格上三角 True | 不泄漏（True 表示遮住） |
| `nn.MultiheadAttention` 误传下三角 True | 位置 0 对自己的权重为 0，只看未来；泄漏 |
| 浮点掩码（-inf / 0） | 与布尔上三角结果一致 |
| `nn.MultiheadAttention` 只传 `is_causal=True` | RuntimeError，掩码仍要自己给 |
| 全遮住的行 | `need_weights=True` 时为 NaN；`need_weights=False` 时为 0 |
| 不传 / 传 `key_padding_mask` | 真实词的输出随填充向量变化 / 与不填充时一致 |
| `TransformerDecoder(tgt=x, memory=x)` 只给 `tgt_mask` | 泄漏；再给 `memory_mask` 后不泄漏 |
| `TransformerEncoder` + 因果掩码 / 不给掩码 | 不泄漏 / 泄漏 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 torch 2.14.1 CPU 版；全遮住的行得到 NaN 还是 0 取决于走哪条计算路径，其他版本、设备或后端可能不同，不要依赖这个值，应当避免出现全遮住的行。
- 扰动检验只能发现「依赖了未来」，不能证明模型在其他输入上同样不泄漏；它适合作为单元测试的一部分。
- 模型是随机初始化的单层结构，不涉及训练效果。

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
