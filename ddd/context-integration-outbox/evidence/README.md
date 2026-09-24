# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 五个场景的结果（键、事实） |
| `final-tables.txt` | 毒消息场景结束时的 outbox 状态、死信与一条 payload，UUID 替换为 `<uuid>` |
| `container.txt` | MySQL 镜像、JDBC 驱动、Gson 与 JDK 镜像 |
| `environment.txt` | 运行环境 |
