# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 8 种条件下每次调用的分配字节数，JDK 21 与 25 各一组（行首 `jdk21.`、`jdk25.`） |
| `jdk-versions.txt` | 两个镜像的 `java -version` |
| `environment.txt` | 运行环境 |
