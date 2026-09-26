# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `facts.tsv` | 每种循环的结果 |
| `boot-failure-analysis.txt` | Spring Boot 启动失败时打印的说明 |
| `async-retry-log.txt` | `@Async` 循环在启动时留下的 INFO 日志 |
| `test-results.txt` | 用例名与结果 |
| `maven-test.log` | Maven 输出 |
| `dependencies.txt` | 依赖版本 |
| `environment.txt` | 运行环境 |

规范化：Maven 输出中的仓库路径替换为 `/workspace`。
