# 验证记录：误删恢复：全量备份与按 GTID 重放

> 按 [验证标准](../../docs/verification-standard.md) 的十个部分记录。

## 一、待验证结论

1. `mysqldump --single-transaction --source-data=2 --set-gtid-purged=ON` 的全量备份在隔离实例导入后，业务校验和与对照实例在备份时刻相同。
2. 用复制 SQL 线程重放归档的 binlog，`UNTIL SQL_BEFORE_GTIDS = <误删 GTID>` 恰好停在误删之前，误删前已提交的合法订单全部在，业务不变量成立。
3. 误删与合法写入发生在同一秒内，按秒截断无法把它们分开。
4. 以空事务占用误删的 GTID 后继续重放，最终四张表的业务校验和与没有误删的对照实例完全相同，GTID 集合与 source 相同。
5. 备份文件损坏一个字节时 SHA-256 与清单不一致；如果跳过校验强行导入，退出码为 0，但业务校验和与不变量能发现问题。
6. 归档缺少一个 binlog 文件时重放不报错，但目标 GTID 集合不完整，业务校验和不同。

## 二、适用版本与环境

- MySQL 8.4.11 与 mysqldump 8.4.11（`compose.yaml` 固定 digest），三个实例各限制 2 CPU、1 GB：`source`、`control`（同一业务流程、没有误删）、`restore`（独立网络，只通过文件获得备份与 binlog）。
- `gtid_mode = ON`、`enforce_gtid_consistency = ON`，其余为默认值；恢复实例 `--relay-log=restore-relay-bin`、`--skip-replica-start`。
- 镜像中没有 `mysqlbinlog`；误删事务通过 `SHOW BINLOG EVENTS` 定位。

## 三、场景与数据集

- `shop` 库：`inventory`（100 个 SKU，各 100 万）、`orders`、`order_items`、`operation_markers`；金额为整数分，全部为虚构数据。
- 存储过程 `place_order(n)` 在一个事务中写一张订单、三条明细、扣三次库存、记一条标记，数值全部由 `n` 决定。
- 业务校验（`schema/02-checks.sql`）：四张表按业务字段计算的 CRC32 之和（不含自增 id 与时间），以及四条不变量：订单金额等于明细之和、库存守恒、标记连续、没有孤儿明细。

## 四、执行步骤

1. source 与 control 各下单 1—1000；source `FLUSH BINARY LOGS` 后全量备份，记录退出码、大小、SHA-256、GTID_PURGED 与 binlog 起点。
2. 两边各下单 1001—1200；source `FLUSH BINARY LOGS`。
3. source 上一个会话每 20ms 下单一笔（1201—1250），0.5 秒后另一个会话执行 `DELETE FROM shop.order_items WHERE order_id > 0`；control 下单 1201—1250，没有误删。
4. 归档 source 的全部 binlog 并记录 SHA-256，扫描 `SHOW BINLOG EVENTS` 定位唯一一笔删除 `shop.order_items` 的事务。
5. 失败路径一：改掉备份中库存表的一个数字，校验和比对拒绝恢复；再强行导入并做业务校验。
6. 恢复：校验备份 → 在 restore 上 `RESET BINARY LOGS AND GTIDS` 并导入 → 基线校验 → 把归档 binlog 作为 relay log 放入数据目录并重启 → `START REPLICA SQL_THREAD UNTIL SQL_BEFORE_GTIDS` → 校验 → 空事务占用误删 GTID → 继续重放到目标 GTID 集合 → 校验与冒烟查询；每一步记录时间。
7. 失败路径二：重新导入备份，去掉备份之后的第一个 binlog 再重放，检查 GTID 集合与业务校验。

## 五、原始证据索引

| 文件 | 内容 |
|---|---|
| `evidence/timeline.tsv` | 备份、写入、误删、发现与恢复各阶段的 UTC 时间 |
| `evidence/backup-manifest.tsv、backup-stderr.txt` | 备份命令、退出码、大小、SHA-256、GTID_PURGED 与 binlog 起点 |
| `evidence/binlog-list.tsv、binlog-archive-manifest.tsv、orders-per-binlog.tsv` | 归档的 binlog 列表、SHA-256 与每个文件中的订单数 |
| `evidence/bad-delete.tsv、bad-transaction.tsv、markers-around-incident.tsv` | 误删的提交时间、行数、GTID 与前后订单的提交时间 |
| `evidence/*-checks.tsv` | 各阶段的业务校验和与不变量 |
| `evidence/restore-*-gtid.txt、source-gtid-executed.txt` | 恢复各阶段与 source 的 GTID 集合 |
| `evidence/failure-*.tsv` | 两个失败路径的结果 |
| `evidence/restore-smoke.tsv` | 只读冒烟查询 |
| `evidence/assertions.txt` | 断言结果 |

`*-checks.tsv` 的每一行是一项检查：前四行为「行数、业务校验和」，其余为不变量的违反数。全量备份与 binlog 文件本身只保存在 `build/work/`，证据中保存它们的清单与 SHA-256。

## 六、实际结果

| 结论 | 实际结果（`evidence/assertions.txt`） |
|---|---|
| 基线 | 导入后业务校验和与对照实例在备份时刻相同 |
| 停在误删之前 | GTID `:1239`；订单 1,225 笔，不变量全部成立 |
| 同一秒 | 误删提交于 18:16:56.190794；同一秒内有订单 1217—1250，其中 25 笔在误删之后 |
| 跳过并补回 | 订单 1,250 笔，四张表的业务校验和与对照相同；GTID 集合与 source 相同；只恢复到误删之前会丢 25 笔合法订单 |
| 损坏的备份 | SHA-256 不一致，导入前停止；强行导入退出码 0，库存校验和不同，1 个 SKU 不满足不变量 |
| binlog 缺口 | 缺 `binlog.000003`：没有应用错误，目标集合不是子集，缺 `:1014-1213`；订单 1,050 笔 |
| 耗时 | 可读 0.4s、可校验 5.7s、可切换 5.8s（演练规模，不能外推） |

## 七、结果解释

- 导入带 `GTID_PURGED` 的备份后，已包含在备份中的事务在 `gtid_executed` 中，SQL 线程重放全部归档时会自动跳过它们，所以不需要精确计算起点。
- `UNTIL SQL_BEFORE_GTIDS` 以事务为单位停止，与时间戳精度无关。
- relay log 索引中缺少一个文件时，SQL 线程只是依次读取剩下的文件，GTID 允许出现空洞，所以不会报错；只有检查目标集合与业务数据才能发现。
- 损坏的一个数字仍是合法的 SQL，所以导入成功。

## 八、误差、限制与不能推出的结论

- 数据只有 1,250 笔订单，耗时不能外推；可校验的 5.7 秒主要是重启恢复实例与轮询。
- 跳过误删是否安全取决于业务：本演练中误删之后的订单不依赖被删的明细。
- 只验证了逻辑备份；物理备份、Clone、延迟副本没有做实验。
- 对照实例在真实事故中通常不存在，实际应以误操作前的源端校验和、业务对账与不变量代替。

## 九、复现与清理命令

```bash
make verify     # 约 1 分钟，输出到 build/run（备份与 binlog 在 build/work）
make evidence   # 重新采集 evidence/
make clean      # 删除三个实例、网络与数据卷
```

## 十、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
