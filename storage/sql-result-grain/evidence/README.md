# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 30 条查询的结果（键、值），与 `schema/expected.tsv` 的前两列逐行一致 |
| `mysql-version.txt` | MySQL 版本与 `sql_mode` |
| `environment.txt` | 运行环境与镜像 digest |
