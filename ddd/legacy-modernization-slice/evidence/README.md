# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `facts.tsv` | 字符化、影子比对与分流的结果 |
| `approved-edge-cases.tsv` | 已批准输出中的 7 个边界用例 |
| `approved-digest.txt` | 已批准文件的 SHA-256 |
| `test-results.txt` | 用例名与结果 |
| `maven-test.log` | Maven 输出 |
| `environment.txt` | 运行环境 |

规范化：Maven 输出中的仓库路径替换为 `/workspace`。
