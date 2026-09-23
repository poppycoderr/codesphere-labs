# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/soft-delete*.txt` | 三种写法的执行结果与重复插入的报错 |
| `evidence/ip.txt` | 转换函数、网段查询结果、字符串比较与索引大小 |
| `evidence/hot-row.tsv` | 各线程数与写法下的吞吐、最大延迟与锁等待次数 |
| `evidence/server.txt` | 版本与持久化参数 |
| `evidence/assertions.txt` | 断言结果 |
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：无：输出为原始内容。
