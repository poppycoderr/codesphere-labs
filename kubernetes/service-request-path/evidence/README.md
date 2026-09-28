# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 各步骤的结果（键、事实） |
| `iptables-nat-worker.txt` | 工作节点 nat 表的完整规则 |
| `dns-short.log`、`dns-external.log`、`dns-fqdn.log` | 三次解析期间 CoreDNS 的日志 |
| `kind-config.yaml` | 集群配置 |
| `cluster-version.txt`、`environment.txt` | Kubernetes、kind、kindnet 版本与运行环境 |
