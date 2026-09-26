# 验证记录：深分页与索引的写入代价

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **`LIMIT offset` 的代价随深度线性增长，游标分页不变**（50 万行，取 20 行，20 次平均）：

   | 位置 | `LIMIT offset, 20` | `WHERE id > ? LIMIT 20` |
   |---|---|---|
   | 第 0 行之后 | 读取 21 行，0.18ms | 读取 20 行，0.14ms |
   | 第 1 万行之后 | 读取 10,021 行，1.12ms | 读取 20 行，0.20ms |
   | 第 10 万行之后 | 读取 100,021 行，9.64ms | 读取 20 行，0.17ms |
   | 第 40 万行之后 | 读取 400,021 行，38.16ms | 读取 20 行，0.17ms |

   每个深度两种写法返回的结果相同。
2. **行构造器写法的游标不走范围扫描**：按 `(created_at, id)` 倒序翻 50 页，两种写法都取到 1000 行且没有重复：
   - `WHERE (created_at, id) < (?, ?)`：总共读取 25,550 行，第 50 页读取 1,001 行，执行计划是从索引末尾开始的反向扫描加过滤；
   - `WHERE created_at < ? OR (created_at = ? AND id < ?)`：总共读取 1,050 行，第 50 页读取 21 行，执行计划是索引范围扫描。
3. **每个二级索引都有写入代价**：写入 20 万行（每 2000 行一批），3 轮中位数：0 个二级索引 1,172ms，2 个 1,560ms，5 个 2,638ms；二级索引分别占 0、16.0 MB、39.6 MB，数据本身 11.5 MB。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/container.txt`](evidence/container.txt)。MySQL 8.4.11（2 CPU、1 GB，固定 digest），默认参数，Connector/J 8.0.27（`rewriteBatchedStatements=true`）。

## 三、执行步骤

`scripts/verify.sh` 启动共享的 MySQL 容器，在 JDK 21 容器中运行 `src/Pagination.java`。读取行数取自同一连接上执行语句前后会话级 `Handler_read_*` 的差值；耗时为预热 5 次后 20 次的平均值。执行计划写入输出目录。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/output.tsv` | 三组对照的结果 |
| `evidence/pagination-plans.txt` | 两种分页在四个深度下的 `EXPLAIN ANALYZE` |
| `evidence/time-cursor-plans.txt` | 两种时间游标写法的 `EXPLAIN ANALYZE` |
| `evidence/container.txt` | 镜像与驱动版本 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- `id` 从 1 连续递增，所以「第 N 行之后」的游标值就是 N；真实表有删除和空洞时，游标取上一页最后一行的值即可，结论不变。
- 写入耗时来自单连接、批量写入、默认刷盘参数，数字取决于机器；断言只要求索引越多越慢。没有测量并发写入时的锁争用与页分裂。
- 行构造器比较不能用于范围扫描，是 MySQL 8.4.11 在这个查询形态下的行为；手册的「Row Constructor Expression Optimization」一节给出了同样的改写建议。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-26 | 首次建立，全部断言通过 | SQL 调优篇新增第五、六节，数字取自本次证据 |
