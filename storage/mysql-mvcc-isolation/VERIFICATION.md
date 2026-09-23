# 验证记录：MVCC 与隔离级别

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. T1—T5 时序：RR 下事务内第二次快照读仍是 100，RC 下是 80；两者的 `FOR UPDATE` 当前读都是 80
2. RR 的快照在第一次一致性读时建立：`BEGIN` 后没有读过，第一次读到 80；`START TRANSACTION WITH CONSISTENT SNAPSHOT` 读到 100
3. RR 下快照读看不到其他事务新插入的 10 行，`UPDATE` 当前读却改到 10 行，之后快照读能看到这 10 行
4. 幻读的三种读法：RR 快照读看不到新行且不阻止插入；RR 加锁读阻止插入（`ERROR 1205`）；RC 加锁读不阻止插入，第二次读到新行
5. 无索引的 `UPDATE`：RR 下锁住扫描到的所有行，另一个事务更新不匹配的行也被阻塞；RC 下半一致性读跳过，更新成功 3 行
6. 长事务持有快照时 History list length 随其他事务的提交持续上涨（每轮 2,000 个单行事务，0 → 10,000），提交后回落到 0

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：mysql 8.4.11（`shared/docker/mysql84`），容器限制 2 CPU、1 GB。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 启动共用的 MySQL 容器，每个场景前重建表（`schema/setup.sql`）
2. 会话 A 在后台执行带 `DO SLEEP` 的事务脚本，会话 B 在固定延时后执行修改；探测语句的锁等待超时为 1 秒
3. 长事务场景：会话 A 以 `WITH CONSISTENT SNAPSHOT` 开启事务并保持 30 秒，会话 B 调用存储过程执行 5 轮 × 2,000 个单行 `UPDATE`，每轮后读取 `trx_rseg_history_len`

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/timeline-rr.txt、timeline-rc.txt` | 两种隔离级别下 T2、T4、T5 的读取结果 |
| `evidence/snapshot-begin.txt、snapshot-consistent.txt` | 快照建立时机 |
| `evidence/snapshot-vs-current.txt` | 快照读与当前读混用 |
| `evidence/phantom-*.txt` | 幻读的三种读法与插入探测 |
| `evidence/semi-consistent-*.txt` | 无索引 UPDATE 在 RR 与 RC 下的探测结果 |
| `evidence/long-transaction.txt` | 长事务持有期间与提交后的 History list length |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/`。

## 五、误差、限制与不能推出的结论

- 时序依赖固定延时（会话之间间隔 2 秒），在负载很高的机器上可能需要加大延时。
- History list length 按提交的更新事务计数，不是按行；数值只说明趋势。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 第四、五节 SQL 时序在 MySQL 8.4.11 上实测 | — |
| 2026-09-24 | 迁入本仓库，补充幻读三种读法、半一致性读与长事务 | 是：第八节加入长事务实测 |
