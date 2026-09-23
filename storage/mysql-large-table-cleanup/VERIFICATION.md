# 验证记录：大表清理

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 一次性删除 100 万行：1 个事务，约 3.4 秒，binlog 174.7MB
2. 按条件 `LIMIT 10000` 分批删除：100 批、100 个事务，单批最长约 90ms，binlog 总量与一次性删除相同（约 175MB），每批约 1.75MB
3. 删除 31 万行（约 10%）时优化器放弃 `idx_created` 选择全表扫描，锁住全部记录，范围外的更新与插入全部阻塞
4. 用 `INDEX` 提示走 `idx_created` 时，范围内阻塞，范围外的更新、边界值与远处的插入约 2ms 完成
5. 条件列没有索引时，范围外的更新与插入同样阻塞
6. 删除 100 万行后表文件大小不变（664MB）；`OPTIMIZE TABLE` 以重建代替，之后约 508MB
7. 表尾有一条补写的旧数据时，按 `MIN(id)`—`MAX(id)` 每 1 万个主键一批：301 批，其中 200 批为空
8. `DROP PARTITION` 删除 100 万行的分区：毫秒级，binlog 位点 158 → 377，只记录一条 DDL

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：mysql 8.4.11（`compose.yaml` 固定 digest），容器限制 2 CPU、3 GB，`innodb_buffer_pool_size = 1G`，开启 binlog，其余持久化参数为默认值。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 启动容器，确定性生成 300 万行 `event_log`，并复制出 `event_log_copy`、`event_log_range` 与分区表 `event_log_p`
2. 一次性删除：`FLUSH BINARY LOGS` 后执行删除，统计新 binlog 文件的字节数与 Xid 事件数
3. 锁范围：会话 A 执行删除后保持 40 秒，期间保存执行计划与 `data_locks` 汇总，会话 B 逐条探测（锁等待超时 2 秒），最后回滚
4. 分批删除：存储过程循环删除，每批耗时记在调用方会话的临时表中（临时表不写入行格式 binlog）
5. 等待 purge 后记录文件大小，执行 `OPTIMIZE TABLE`
6. 主键区间：在表尾插入一条旧数据后按主键区间删除
7. 删除分区，记录 binlog 位点与事件

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/one-shot-delete.txt、batch-delete.txt、batch-delete-per-batch.txt` | 两种删除方式的耗时、事务数与 binlog 字节数，以及每批的行数与耗时 |
| `evidence/lock-probe-*.txt` | 三种情况的执行计划、`data_locks` 汇总与探测结果 |
| `evidence/file-size.txt、optimize-message.txt` | 删除前后与重建后的 `.ibd` 大小 |
| `evidence/range-delete.txt` | 主键区间删除的批次统计 |
| `evidence/drop-partition.txt` | 分区行数、binlog 位点与记录的语句 |
| `evidence/assertions.txt` | 断言结果 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/`。

## 五、误差、限制与不能推出的结论

- 优化器是否放弃索引取决于统计信息与删除范围占比，不同数据分布下的临界点不同；本实验只说明「有索引不一定会用」。
- `data_locks` 汇总中锁的数量比 300 万多出约 3.8 万个，是每个数据页的 supremum 伪记录。
- 耗时是单机容器的结果；文章中「每批 1 万行」只是实验配置，不是通用推荐值。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 在 MySQL 8.4.11 容器中实测，数字见文章 | — |
| 2026-09-24 | 迁入本仓库，确定性造数重跑；新增 INDEX 提示与执行计划记录 | 是：binlog 145MB → 175MB（行更宽）；第三节改为「有索引时优化器仍可能选全表扫描」；主键区间改为刻意构造的补写数据 |
