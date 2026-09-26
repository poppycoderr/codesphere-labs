# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `facts.tsv` | 测试记录的观察结果（键、事实） |
| `test-results.txt` | 每个测试的通过情况 |
| `maven-test.log` | `mvn test` 输出，路径与用户名已替换为占位符 |
| `dependencies.txt` | 运行时依赖版本 |
| `environment.txt` | 运行环境与 MySQL 镜像 digest |
