# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 主实验的结论行 |
| `async-thread-by-jdk.tsv` | 三个 JDK 版本、三种 CPU 数下 `supplyAsync` 的行为 |
| `timings.log` | 各场景的原始耗时 |
| `environment.txt` | 运行环境与镜像 digest |
