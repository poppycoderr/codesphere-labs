# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 每个场景一行：键与结论 |
| `container.txt` | 使用的镜像 |
| `environment.txt` | 运行环境与镜像 digest |

## MongoDB 9.0.4 新增证据

| 文件 | 内容 |
|---|---|
| `invalid-election-timeout.json` | 9.0.4 拒绝旧选举超时配置的原始错误，code=93 |
| `client-versions.json` | Python、PyMongo 及传递依赖 dnspython 的实际版本 |
| `nonvoting-before-config.json` / `nonvoting-after-config.json` | 正常 reconfig 前后的完整副本集配置 |
| `nonvoting-before-status.json` / `nonvoting-after-status.json` | 写入门槛、选举多数、成员状态及 opTime |
| `nonvoting-before-write-error.json` | 改配置前 majority 写 e 的写关注超时原文，code=64 |
| `nonvoting-reconfig-result.json` | 未使用 force 的 replSetReconfig 命令响应 |
| `nonvoting-writes.json` / `nonvoting-majority-read.json` | 三次写确认和调整后的 majority 读结果 |
| `nonvoting-summary.json` | 根据实际结果生成的汇总，与原始证据配合核对 |
| `container-states.json` | Docker CLI 采集的服务状态，断言 m1 退出、m2 与 arb 运行 |
| `source-manifest.json` | 核心源码、运行脚本、compose 和依赖文件 SHA-256 |

BSON 值以 Extended JSON 保留；不修改数据 ID、确认数、错误码或版本。容器状态只保留实验服务名及状态，不保存包含宿主机路径的 Compose Labels。没有按结果挑选多次运行的输出，make evidence 重新完整运行并覆盖本目录。
