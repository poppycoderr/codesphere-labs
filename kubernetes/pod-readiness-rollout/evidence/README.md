# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 各场景的结果（键、事实）；`*.client` 为压测客户端输出的 JSON |
| `stuck-pods.txt` | 坏版本卡住时的 `kubectl get pods` |
| `stuck-rollout-status.txt` | 坏版本发布时 `kubectl rollout status` 的输出 |
| `cluster-version.txt`、`environment.txt` | Kubernetes 与 kind 版本、运行环境与镜像 digest |
