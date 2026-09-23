# Redo 与 Undo：kill -9 崩溃恢复、默认持久化参数与刷盘参数调低后的丢失

对应文章：[mysql-redo-log-and-undo-log.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-redo-log-and-undo-log.md)。

用 SIGKILL 杀掉 mysqld 再重启，验证已提交与未提交事务的恢复结果；读取默认持久化参数、redo 文件与 undo 表空间；客户端逐条确认提交时直接 SIGKILL，比较 `innodb_flush_log_at_trx_commit` 为 1、2、0 时丢失的已确认提交与 binlog 分叉。

**轻量实验**：保留核心脚本、一条验证入口、原始输出和简要验证记录。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 2 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
