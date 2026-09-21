# 验证记录：ORDER BY id LIMIT 让优化器放弃二级索引

## 一、待验证结论

| # | 文章中的结论 |
|---|---|
| 1 | 待处理数据集中在表尾时，`state = 0 AND event_type IN (...) AND deleted = 0 ORDER BY id LIMIT 100` 被优化器选择为沿主键扫描，实际读取约 285 万行 |
| 2 | 同样的写法，数据在表头（`state = 2`）时主键扫描只读几百行，优化器的假设成立 |
| 3 | `IN` 换成单个等值条件时，优化器直接选择二级索引，因为结果天然按 `id` 有序 |
| 4 | `FORCE INDEX`、`prefer_ordering_index=off`、`ORDER BY id + 0` 三种写法都改走二级索引，读取全部匹配行后排序，比原查询快一个数量级以上 |
| 5 | 按取值拆成 `UNION ALL` 只需读取约 200 行，比其他修复再快两个数量级 |
| 6 | 所有修复写法返回的 100 条记录与原查询完全相同 |

## 二、适用版本与环境

- MySQL 8.4.11，镜像 `mysql:8.4.11@sha256:85b9bf2e…c638f2a`，见 [`evidence/container.txt`](evidence/container.txt)。
- 容器限制 2 CPU、2 GB 内存；`innodb_buffer_pool_size = 1G`；默认隔离级别 REPEATABLE-READ；`prefer_ordering_index` 默认开启，见 [`evidence/database-status.txt`](evidence/database-status.txt)。
- 宿主机 macOS arm64、10 核、Docker 29.7.2，见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、场景与数据集

`schema/02-seed.sql` 由 id 确定性计算每个字段，不使用随机数，任何机器生成的数据完全相同：

- 3,000,000 行，数据约 495 MB、索引约 69 MB；
- id 在最后 5%（2,850,001 以后）且能被 3 整除的 50,000 行为待处理（`state = 0`），其余为已处理；
- `event_type` 由 `CRC32(id) % 10` 决定：PAY 40%、REFUND 10%、SHIP 30%、NOTIFY 20%；
- `deleted` 约 2% 为 1。

目标查询实际匹配 24,541 行，第一条出现在 id 2,850,003，见 [`evidence/dataset.txt`](evidence/dataset.txt)。

## 四、执行步骤

```text
docker compose up --wait（固定 digest、2 CPU、2 GB）
→ 建表并确定性生成 300 万行 → ANALYZE TABLE
→ 采集版本、配置、表大小、索引基数、数据分布
→ 每条查询：预热 3 次 → 记录返回的 id → 单次执行并记录 Handler 与缓冲池计数
            → EXPLAIN FORMAT=JSON → EXPLAIN ANALYZE 采样 7 次
→ summarize.py 汇总并断言
```

除 `queries/` 中的 SQL 外，所有查询在同一个容器、同一份数据、同一轮运行中执行，条件相同。

## 五、原始证据索引

| 结论 | 证据 |
|---|---|
| 1 | [`evidence/before/00-original/`](evidence/before/00-original/) |
| 2 | [`evidence/control/10-control-processed-at-head/`](evidence/control/10-control-processed-at-head/) |
| 3 | [`evidence/control/11-control-single-value/`](evidence/control/11-control-single-value/) |
| 4、5 | [`evidence/after/`](evidence/after/) |
| 6 | 各目录的 `result-ids.txt`；[`evidence/summary.md`](evidence/summary.md) 的「结果 SHA-1」列 |
| 断言 | [`evidence/assertions.txt`](evidence/assertions.txt) |

## 六、实际结果

摘自 [`evidence/summary.md`](evidence/summary.md)（7 次采样）：

| 查询 | 使用的索引 | Handler 读取次数 | 中位数 ms | 最小—最大 ms | 缓冲池页请求 |
|---|---|---:|---:|---:|---:|
| 原查询 | PRIMARY | 2,850,622 | 569 | 564—585 | 49,171 |
| `FORCE INDEX` | idx_state_event_deleted | 24,543 | 27.5 | 27.2—27.8 | 98,232 |
| `prefer_ordering_index=off` | idx_state_event_deleted | 24,543 | 27.5 | 26.9—27.7 | 98,261 |
| `ORDER BY id + 0` | idx_state_event_deleted | 24,543 | 27.7 | 27.2—28.0 | 98,232 |
| `UNION ALL` | idx_state_event_deleted | 401 | 0.448 | 0.426—0.452 | 1,226 |
| 对照：数据在表头 | PRIMARY | 205 | 0.077 | 0.075—0.087 | 52 |
| 对照：单个等值条件 | idx_state_event_deleted | 100 | 0.16 | 0.156—0.17 | 615 |

原查询的执行计划（`evidence/before/00-original/explain-analyze.txt` 第 1 次采样）：

```text
-> Limit: 100 row(s)  (cost=35 rows=100) (actual time=565..566 rows=100 loops=1)
    -> Filter: ((task_event.deleted = 0) and (task_event.state = 0) and (task_event.event_type in ('PAY','REFUND')))  (cost=35 rows=100) (actual time=565..566 rows=100 loops=1)
        -> Index scan on task_event using PRIMARY  (cost=35 rows=6893) (actual time=0.049..446 rows=2.85e+6 loops=1)
```

优化器预计沿主键读 6,893 行，实际读了 285 万行。5 种写法返回的 id 校验和都是 `018db53b361c`。所有查询的物理读都为 0。

## 七、结果解释

- **估算与实际的差距**：`explain.json` 中匹配行的估算是 41,969 行，占全表约 1.45%，优化器据此推断沿主键平均每 69 行就能遇到一条，读 6,893 行即可凑够 100 条。实际数据全部集中在最后 5%。
- **缓冲池页请求说明回表成本**：三种「走索引再排序」的写法读取行数只有原查询的 0.9%，页请求却是原查询的 2 倍。原因是主键扫描在一个页里连续读取多行，而二级索引的每一条匹配都要回到聚簇索引取整行。它们仍然快 20 倍，因为热缓存下每次页请求的开销很小，扫描和过滤 285 万行才是主要成本。
- **`UNION ALL` 的 401 次读取**：两个子查询在二级索引上各读 100 行（`Handler_read_key` 2 次、`Handler_read_next` 198 次），合并后的临时表扫描计为 `Handler_read_rnd_next` 201 次。
- **单个等值条件**：三列都是等值时，二级索引叶子节点内的记录按主键有序，`ORDER BY id LIMIT 100` 直接读前 100 条，没有排序步骤。

## 八、误差、限制与不能推出的结论

- 这是热缓存下的结果（物理读为 0）。冷启动或缓冲池放不下数据时，主键扫描会产生大量磁盘读，差距可能更大，本实验没有测量。
- 耗时来自 `EXPLAIN ANALYZE`，包含计时开销；绝对值随 CPU、Docker 虚拟化层和宿主机负载变化。只应比较同一轮运行中的相对关系。
- 数据分布是人为构造的极端倾斜。文章首次发布时用的是另一份规律生成的数据（原查询 616ms、强制索引 34ms），本实验用确定性脚本重新生成，数值略有不同，结论一致。
- `prefer_ordering_index` 的行为从 MySQL 8.0.21 起可用，本实验只验证了 8.4.11。
- 跨平台复核：GitHub Actions（x86_64、4 核、JDK 21.0.12、Docker 28.0.4）上同一脚本的执行计划、Handler 读取次数、缓冲池页请求（原查询 49,198，本机 49,171）与结果校验和一致；耗时为原查询 754ms、三种修复约 47ms、`UNION ALL` 0.644ms，比本机慢 1.3—1.7 倍，相对关系不变。原始输出保存在该次运行的 artifact 中（`scheduled-recheck` 运行 35633483096，artifact 默认保留 90 天），没有提交到仓库。

## 九、复现与清理命令

```bash
make setup      # 启动容器并造数
make verify     # 完整验证，输出到 build/run
make evidence   # 重新生成 evidence/
make clean      # docker compose down -v 并删除 build/
```

可通过环境变量调整：`WARMUP`（默认 3）、`SAMPLES`（默认 7）、`FORCE_SEED=1`（强制重新造数）。

## 十、验证历史

| 日期 | 环境 | 结果 | 文章是否需要更新 |
|---|---|---|---|
| 2026-09-18 | MySQL 8.4.11（未固定 digest），临时造数脚本 | 原查询 616ms，强制索引 34ms，`UNION ALL` 0.43ms | — |
| 2026-09-22 | MySQL 8.4.11 固定 digest，2 CPU / 2 GB，确定性造数 | 原查询 569ms，三种修复约 27.5ms，`UNION ALL` 0.448ms；结论一致 | 是：读取行数（20,000 → 24,541）、估算行数与各项耗时改为本记录的数据 |
| 2026-09-22 | GitHub Actions x86_64，4 核（CI 复核） | 执行计划与计数一致；原查询 754ms、修复约 47ms、`UNION ALL` 0.644ms | 否 |
