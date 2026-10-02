# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 26 条查询的结果（键、值），与 `schema/expected.tsv` 的前两列逐行一致 |
| `explain.txt` | 半开区间与对列使用 `DATE()` 两种写法的执行计划 |
| `mysql-version.txt`、`environment.txt` | MySQL 版本、运行环境与镜像 digest |
