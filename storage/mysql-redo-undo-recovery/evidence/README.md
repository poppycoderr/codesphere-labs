# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/defaults.txt、redo-files.txt` | 默认参数、undo 表空间、redo 状态变量与 redo 文件列表 |
| `evidence/crash-*.txt` | 崩溃前后的数据、两个会话的输出、重启日志（时间戳与线程号已替换为占位符） |
| `evidence/flush-<参数>-trial-<n>.txt` | 每次刷盘参数实验的确认数、行数与 binlog 事件数 |
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：重启日志中的时间戳与线程号替换为 `<timestamp> <thread>`；其余为原始输出。
