# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/facts.txt` | `Connection` 方法统计、只读视图与不可变集合 |
| `evidence/connection-defaults.txt` | default 方法的行为 |
| `evidence/stack-frames.txt` | 装饰器与动态代理的栈帧数 |
| `environment.txt` | 操作系统、CPU 数、JDK 版本 |

规范化：测试报告中的总耗时替换为 `<elapsed>`，编译错误中的绝对路径改为相对路径，时区名替换为 `<zone>`，死锁日志中的时间戳与线程号替换为占位符；其余为原始输出。
