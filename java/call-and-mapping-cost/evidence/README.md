# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `jmh.csv`、`jmh.log` | JMH 原始结果与输出 |
| `summary.tsv` | 每个基准的平均耗时与误差 |
| `mapstruct-generated.txt` | MapStruct 生成的源文件列表 |
| `compat-*.txt` | 旧版字节码库在 JDK 21 上的输出（前 3 行） |
| `bad-mapper.txt` | 漏映射字段时的编译输出 |
| `maven-package.log`、`environment.txt` | 构建日志与运行环境 |
