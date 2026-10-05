# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 四种发压方式的样本数、目标与实际速率、卡顿窗口内发出的请求数；`from_send` 与 `from_intended` 两行分别是从实际发送时刻、预定发送时刻计时的慢样本数（超过 100 毫秒）与分位数（毫秒） |
| `environment.txt` | 运行环境与 JDK 镜像 digest |
