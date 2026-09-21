# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/before-correct-day.txt` | 拆分前，白天 |
| `evidence/before-correct-evening.txt` | 拆分前，晚间 |
| `evidence/before-bug-day.txt` | 拆分前，注入 bug |
| `evidence/after-correct.txt` | 拆分后，正确版本 |
| `evidence/after-bug.txt` | 拆分后，注入 bug |
| `evidence/time-setup.txt` | 时区设置方式 |
| `environment.txt` | 操作系统、CPU 数、JDK、Docker 版本 |

规范化：测试报告中的总耗时替换为 `<elapsed>`，编译错误中的绝对路径改为相对路径，时区名替换为 `<zone>`，死锁日志中的时间戳与线程号替换为占位符；其余为原始输出。
