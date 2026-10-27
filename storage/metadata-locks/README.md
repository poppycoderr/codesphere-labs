# 元数据锁：一条 ALTER 卡住所有查询

对应文章：[metadata-locks.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/metadata-locks.md)。

`scripts/verify.sh` 在后台开三个会话：会话 1 开事务读一行后 `SLEEP`（不提交），会话 2 执行 `ALTER TABLE ... ADD COLUMN ..., ALGORITHM=INSTANT`，会话 3 执行普通查询。等待期间读 `performance_schema.metadata_locks`、`performance_schema.processlist` 与 `sys.schema_table_lock_waits`，从视图里找出阻塞者并 `KILL`，记录 ALTER 与查询各等了多久。第二个场景同样挡住 ALTER，只把 `innodb_lock_wait_timeout` 设为 1、`lock_wait_timeout` 设为 3，看哪一个生效。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
