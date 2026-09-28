# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `jdk25-off.tsv`、`jdk25-on.tsv`、`jdk26-off.tsv`、`jdk26-on.tsv` | 每种配置下的字段偏移、单个实例大小、HashMap 的堆占用与分类明细 |
| `jdk25-flags.txt`、`jdk26-flags.txt` | `java -version` 与相关参数的默认值 |
| `environment.txt` | 运行环境 |
