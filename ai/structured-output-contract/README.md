# 模型输出的结构契约

对应文章：[structured-output-contract.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/structured-output-contract.md)。

`src/lab.py` 在固定 digest 的 `python:3.14.8-slim` 容器里运行，`pydantic==2.14.1` 从 PyPI 安装（缓存在仓库的 `.cache/pip`）。模型的输出是写死的样本，不调用任何模型。依次验证：同一组字段值在 Pydantic 默认（宽松）模式与 `strict=True, extra="forbid"` 加范围约束下的结果；模型常见的几种文本形态能否通过 JSON 语法层；「取第一对花括号」的启发式；Schema 通过之后的领域校验（价格以应用持有的目录为准、商品必须存在）；一次「修复」把不合法的输出改合法但换了商品；最多尝试 2 次与 3 次的重试预算。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
