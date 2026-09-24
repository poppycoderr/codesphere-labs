# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `facts.tsv` | 规则检查、调用顺序、失败路径与往返检查的结果 |
| `test-results.txt` | 用例名与结果 |
| `maven-test.log` | Maven 输出 |
| `dependencies.txt` | 解析后的依赖版本 |
| `environment.txt` | 运行环境 |

规范化：Maven 输出中的仓库路径替换为 `/workspace`。
