# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/plans/<查询>/` | 每条查询的 `query.sql`、`explain.tsv`、`explain.json`、`explain-analyze.txt`、`handler.txt`、`icp.txt`、`indexes.txt`（部分） |
| `evidence/summary.md` | 所有查询的 type、key、key_len、估算行数、Extra、Handler 读取、ICP 检查与中位耗时 |
| `evidence/assertions.txt` | 断言结果 |
| `evidence/dataset.txt、container.txt` | 版本、数据分布与容器配置 |
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：无：输出为原始内容。
