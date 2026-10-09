# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `default.tsv` | 默认配置下 7 个场景：服务端发来的证书、客户端的结果或异常 |
| `aia-enabled.tsv` | 只打开 `enableAIAcaIssuers` 时的 AIA 场景 |
| `aia-enabled-and-allowed.tsv` | 同时设置 `allowedAIALocations` 时的 AIA 场景 |
| `java-security-default.txt` | 镜像自带 `java.security` 里 `allowedAIALocations` 的默认值 |
| `environment.txt` | 运行环境与镜像 digest |
