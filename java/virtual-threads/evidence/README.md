# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `throughput.tsv` | 本机 JDK：平台线程池、虚拟线程、20 个连接三组耗时（毫秒） |
| `pinning-local.tsv` | 本机 JDK 21.0.5：`synchronized` 与 `ReentrantLock` 内阻塞的耗时 |
| `pinning-jdk21.tsv` | Docker JDK 21.0.12，6 核 |
| `pinning-jdk25.tsv` | Docker JDK 25.0.4，6 核 |
| `environment.txt` | 运行环境与镜像 digest |
