# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 各项检查的结果（键、事实）；`jdbc.*` 来自 `src/JdbcGuard.java` |
| `mysql-version.txt`、`environment.txt` | MySQL 版本、运行环境、镜像 digest 与驱动 jar 的 sha256 |
