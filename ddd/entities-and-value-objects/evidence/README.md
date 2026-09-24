# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `facts.tsv` | 每个用例记录的事实 |
| `test-results.txt` | 用例名与结果 |
| `maven-test.log` | Maven 输出 |
| `environment.txt` | 运行环境 |

规范化：Maven 输出中的仓库路径替换为 `/workspace`。
