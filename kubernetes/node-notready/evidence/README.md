# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 各步骤的结果（键、事实） |
| `kind-config.yaml` | 集群配置 |
| `cni-conflist.json` | 工作节点上写入的 CNI 配置 |
| `cluster-version.txt`、`environment.txt` | Kubernetes 与 kind 版本、运行环境与镜像 digest |
