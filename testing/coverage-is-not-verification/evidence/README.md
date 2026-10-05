# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `summary.tsv` | 三套测试各自的行覆盖、分支覆盖、变异体总数、被杀死的数量、存活的变异体（算子@行号） |
| `mutations-<测试类>.tsv` | PIT 对每个变异体的结论：算子、行号、状态、杀死它的测试方法 |
| `missing-requirement.txt` | 针对未实现需求的测试的运行结果与 Maven 退出码 |
| `environment.txt` | 运行环境、Maven 镜像 digest 与各插件版本 |
