# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/styles.txt` | 构造器、Builder、类型状态 Builder、Lambda 配置器 |
| `evidence/type-state-missing-javac.txt` | 漏填时的编译错误 |
| `evidence/binary-compatibility.txt` | v1 编译的调用方在 v2 上运行 |
| `environment.txt` | 操作系统、CPU 数、JDK 版本 |

规范化：测试报告中的总耗时替换为 `<elapsed>`，编译错误中的绝对路径改为相对路径，时区名替换为 `<zone>`，死锁日志中的时间戳与线程号替换为占位符；其余为原始输出。
