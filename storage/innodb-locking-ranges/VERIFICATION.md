# 验证记录：InnoDB 行锁的范围：data_locks 与阻塞探测

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. RR 下 `id = 10 FOR UPDATE` 只加主键 10 的记录锁；`id = 12`（不存在）在主键 15 上加间隙锁，阻塞插入 11、14，放行 16，也放行另一个事务的 `id = 13 FOR UPDATE`（间隙锁互相兼容）
2. RR 下 `k = 10 FOR UPDATE`：`idx_k` 10 Next-Key、`idx_k` 15 间隙锁、主键 10 记录锁；间隙锁不阻止修改已有记录
3. RR 下 `id >= 10 AND id <= 15`：主键 10 记录锁、主键 15 Next-Key
4. RR 下无索引条件 `c = 10`：5 条记录加 supremum 全部 Next-Key；RC 下只剩主键 10 一个记录锁
5. 两个事务先后 `FOR UPDATE` 锁住同一个间隙再各自插入：一个成功，另一个收到 `ERROR 1213`，死锁日志显示在等待插入意向锁
6. 商户订单表 2 万行、商户 7 有 2,000 单：只有 `(merchant_id)` 索引时，`UPDATE … WHERE merchant_id = 7 AND external_no = 'A1007'` 锁住 4,005 条索引记录（二级索引 2,004 个 Next-Key 与 1 个间隙锁、主键 2,000 个记录锁），同商户的更新与插入被阻塞、其他商户放行；加上 `(merchant_id, external_no)` 后只剩 3 个锁，全部放行

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：mysql 8.4.11。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 启动 MySQL（`shared/docker/mysql84`，项目名 `csl-innodb-locking`）
2. 每个场景重建 5 行的表，持锁会话执行语句后保持事务，读取 `performance_schema.data_locks`
3. 探测会话设置 `innodb_lock_wait_timeout = 1`，逐条执行探测语句
4. 两个会话按固定延时交错执行，复现死锁
5. 商户订单：每种索引下重建 2 万行的表，持锁 10 秒，汇总 `data_locks` 并探测三条语句

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/rr-*.txt、evidence/rc-*.txt` | 每条持锁语句的 `data_locks` 与探测结果 |
| `evidence/deadlock-session-a.txt、deadlock-session-b.txt` | 两个会话的输出 |
| `evidence/deadlock-innodb-status.txt` | `SHOW ENGINE INNODB STATUS` 中的死锁段落 |
| `evidence/merchant-single-index.txt、merchant-composite-index.txt` | 商户订单两种索引下的执行计划、`data_locks` 汇总与探测结果 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 死锁日志中的时间戳和线程号已替换为占位符。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 在会话临时目录中实测，数字见文章 | — |
| 2026-09-22 | 迁入本仓库，全部断言通过 | 见上文「误差、限制」中与文章不同的数字 |
| 2026-09-24 | 补齐商户订单场景（文章第六节），锁数量 4,005 与 3、阻塞与放行结果与文章一致 | 否 |
