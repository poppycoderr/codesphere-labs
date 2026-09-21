# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/inherit.txt` | 继承与组合的计数 |
| `evidence/rules.txt` | `addAll` 的声明位置与规则组合 |
| `evidence/sealed.txt` | 密封类型的正常结果 |
| `evidence/sealed-v2-javac.txt` | 新增子类型后的编译错误 |
| `environment.txt` | 操作系统、CPU 数、JDK 版本 |

规范化：测试报告中的总耗时替换为 `<elapsed>`，编译错误中的绝对路径改为相对路径，时区名替换为 `<zone>`，死锁日志中的时间戳与线程号替换为占位符；其余为原始输出。
