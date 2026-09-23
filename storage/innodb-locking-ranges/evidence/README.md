# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/rr-*.txt、evidence/rc-*.txt` | 每条持锁语句的 `data_locks` 与探测结果 |
| `evidence/deadlock-session-a.txt、deadlock-session-b.txt` | 两个会话的输出 |
| `evidence/deadlock-innodb-status.txt` | `SHOW ENGINE INNODB STATUS` 中的死锁段落 |
| `evidence/merchant-*.txt` | 商户订单两种索引下的执行计划、`data_locks` 汇总与探测结果 |
| `environment.txt` | 操作系统、CPU 数、JDK、Docker 版本 |

规范化：测试报告中的总耗时替换为 `<elapsed>`，编译错误中的绝对路径改为相对路径，时区名替换为 `<zone>`，死锁日志中的时间戳与线程号替换为占位符；其余为原始输出。
