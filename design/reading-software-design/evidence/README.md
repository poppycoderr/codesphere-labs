# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/spring-slice.txt` | Spring 容器切片 |
| `evidence/kafka-slice.txt` | Kafka Producer 切片 |
| `evidence/stream-slice.txt` | Stream 切片 |
| `evidence/dependencies.txt` | 解析到的 jar |
| `environment.txt` | 操作系统、CPU 数、JDK、Docker 版本 |

规范化：测试报告中的总耗时替换为 `<elapsed>`，编译错误中的绝对路径改为相对路径，时区名替换为 `<zone>`，死锁日志中的时间戳与线程号替换为占位符；其余为原始输出。
