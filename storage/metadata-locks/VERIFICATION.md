# 验证记录：元数据锁：一条 ALTER 卡住所有查询

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 会话 1（读一行，未提交） | 持有 SHARED_READ，GRANTED |
| ALTER ... ALGORITHM=INSTANT | SHARED_UPGRADABLE GRANTED，EXCLUSIVE PENDING |
| 之后的普通查询 | SHARED_READ PENDING，Waiting for table metadata lock |
| sys.schema_table_lock_waits | 4 行、2 个阻塞者候选，包括正在等待的 ALTER 自己 |
| KILL 会话 1 | ALTER 与查询都随即完成 |
| innodb_lock_wait_timeout=1、lock_wait_timeout=3 | 约 3 秒报 1205 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 等待秒数取决于脚本里的 sleep 安排，断言只检查范围。
- 只演示了表级元数据锁；全局读锁（FLUSH TABLES WITH READ LOCK）、外键关联表上的元数据锁没有覆盖。
- KILL 一个做过大量写入的长事务会触发回滚，回滚期间锁仍不释放；本实验的会话 1 只读，没有回滚代价。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-28 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
