# 验证记录：模型输出的结构契约

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 默认（宽松） | strict + extra=forbid + 范围 |
|---|---|---|
| qty 是字符串 `"3"` | 通过，得到 3 | 拒绝 |
| qty 是 3.0 | 通过，得到 3 | 拒绝 |
| qty 是 3.5 | 拒绝 | 拒绝 |
| qty 是 true | 通过，得到 1 | 拒绝 |
| 多一个 `discount_pct` | 通过，额外字段被丢弃 | 拒绝 |
| qty 是 -2 | 通过 | 拒绝 |

| 场景 | 结果 |
|---|---|
| 包在代码块里 / 前面有一句话 / 截断 | 语法层拒绝 |
| 返回了数组 | 拒绝：不是对象 |
| 取第一对括号的启发式 | 取到 `{"note": "见 {附件}` |
| Schema 通过但价格不是目录价 / 商品不存在 | 领域层拒绝 |
| 修复后的输出 | 三层都通过，但 sku 从 B200 变成 A100；对比应保持不变的字段后拒绝 |
| 重试预算 2 次 / 3 次 | 放弃并返回明确失败 / 第 3 次成功 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 模型输出是手写的样本，用来覆盖常见形态；真实模型的出错比例需要另行评估。
- 只验证 Pydantic 2.14.1 的 `model_validate`（Python 对象输入）；用 `model_validate_json` 直接解析 JSON 时，严格模式对部分类型的处理不同，以官方文档为准。
- 没有覆盖模型提供方的「结构化输出 / JSON Schema 约束解码」功能，它能保证语法和 Schema，但不能保证领域正确。

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
