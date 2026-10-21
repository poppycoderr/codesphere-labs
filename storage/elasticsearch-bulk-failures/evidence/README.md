# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 每个场景一行：键与结论 |
| `container.txt` | 使用的镜像 |
| `environment.txt` | 运行环境与镜像 digest |

| `queue-settings.json` | 节点线程池配置；write=1、队列=1，协调池=2、队列=1000 |
| `queue-before.json` / `queue-after.json` | 原始节点统计；对比 write 与 write_coordination 拒绝计数 |
| `queue-initial-bulk.json` | 每次发送的合成文档 ID、HTTP 状态和完整逐项响应 |
| `queue-retries.json` | 每轮退避秒数、仅失败 ID 和完整逐项重试响应 |
| `queue-final-versions.json` | 所有最终文档 ID 与版本，断言均为 1 |
| `queue-summary.json` / `queue-output.jsonl` | 断言后的本轮汇总，不要求随机计数跨次相同 |
| `get-visibility.json` | 禁用周期刷新时实时 GET 与搜索可见性对照 |
| `source-manifest.json` | 核心脚本、配置和依赖的 SHA-256 |

规范化：节点统计的随机 node ID 替换为 `<node>`，节点时间戳替换为 `<timestamp>`，Docker 容器地址替换为 `<container-address>`。节点名称固定为 `es-lab`。不改变拒绝数、队列数、文档 ID、错误类型、异常原因、退避时间或版本。
