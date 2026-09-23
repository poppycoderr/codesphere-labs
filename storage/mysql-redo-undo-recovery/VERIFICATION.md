# 验证记录：Redo 与 Undo 的崩溃恢复

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 默认参数：`innodb_flush_log_at_trx_commit = 1`、`sync_binlog = 1`、`innodb_redo_log_capacity = 100MB`、2 个 undo 表空间、`innodb_undo_log_truncate = ON`、`innodb_max_undo_log_size = 1GB`、双写开启
2. `#innodb_redo` 目录下共 32 个文件，含 `_tmp` 后缀的备用文件
3. 会话 B 修改后未提交、会话 A 插入并提交，`SIGKILL` mysqld 后重启：id 1 仍是 100，id 3 存在，id 2 不存在；错误日志有 XA crash recovery
4. 客户端逐条自动提交并记录确认，写入进行中 `SIGKILL`：`innodb_flush_log_at_trx_commit` 为 1、2 时重启后行数等于已确认数；为 0 时少于已确认数，且 binlog 中的写入事件多于 InnoDB 中的行

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：mysql 8.4.11（`compose.yaml` 固定 digest，默认配置），容器限制 2 CPU、1 GB。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 启动默认配置的容器，读取参数、redo 文件与 undo 表空间
2. 崩溃恢复：后台会话 B 修改后保持事务，会话 A 插入并提交，`docker compose kill -s KILL` 后 `start`，保存本次启动的错误日志
3. 刷盘参数：设置 `innodb_flush_log_at_trx_commit` 与 `sync_binlog = 0`，容器内的 mysql 客户端逐条执行 `INSERT` 并输出序号（确认日志写在容器可写层），4 秒后 `docker kill -s KILL`，重启后比较确认的最大序号、表中行数与 binlog 中的 `Write_rows` 事件数；`= 0` 时最多重复 3 次

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/defaults.txt、redo-files.txt` | 默认参数、undo 表空间、redo 状态变量与 redo 文件列表 |
| `evidence/crash-*.txt` | 崩溃前后的数据、两个会话的输出、重启日志（时间戳与线程号已替换为占位符） |
| `evidence/flush-<参数>-trial-<n>.txt` | 每次刷盘参数实验的确认数、行数与 binlog 事件数 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/`。

## 五、误差、限制与不能推出的结论

- 故障模型只有「mysqld 进程被 SIGKILL」：宿主机操作系统崩溃、断电、存储写缓存丢失都无法在容器中模拟，`= 2` 在这些故障下的行为没有验证。
- `= 0` 的丢失取决于 SIGKILL 落在后台每秒刷盘的哪个时刻：三次重复中有的丢 1—13 个，有的不丢；断言只要求 3 次中至少一次丢失。
- 4 秒内确认的提交数（约 1.8 万、2.7 万、3.8 万）只用于说明趋势，与硬件有关。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 崩溃恢复与默认值在 MySQL 8.4.11 容器中实测 | — |
| 2026-09-24 | 迁入本仓库；新增刷盘参数与进程崩溃的丢失实验 | 是：第五节补充故障模型边界与实测表格 |
