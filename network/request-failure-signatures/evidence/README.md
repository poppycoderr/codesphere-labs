# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 每个目标在 HttpClient 与 Socket 下的结果、异常链与耗时（键、事实） |
| `netns-config.txt` | 客户端命名空间的路由、`ip route get` 与 iptables 规则 |
| `diagnostics.txt` | 访问之后的邻居表、`ping` 与 `curl` 的输出 |
| `iproute2-version.txt`、`environment.txt` | 工具版本与运行环境 |
