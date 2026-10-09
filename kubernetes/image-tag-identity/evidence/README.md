# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 每一步的节点、镜像引用、拉取策略、运行的内容、摘要代号与 kubelet 事件 |
| `digests.txt` | 本次运行里摘要 A、摘要 B 与两次重复构建的镜像 ID 的原始值 |
| `environment.txt` | 运行环境与镜像 digest |
