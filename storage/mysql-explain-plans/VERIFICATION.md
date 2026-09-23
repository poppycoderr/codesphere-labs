# 验证记录：Explain 执行计划与索引失效

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 按客户查询是 `ref`、`key_len = 4`；客户加日期是 `range`、`key_len = 9`、`Using index condition`；只按日期查询是全表扫描
2. `idx_phone` 的 `key_len` 为 82；`phone` 与数字比较时 `possible_keys` 有 `idx_phone`、`key` 为 `NULL`，全表扫描
3. 覆盖索引 `Using index`；按不在索引中的列排序 `Using filesort`；按索引列倒序 `Backward index scan`
4. 「已支付订单最多的 5 个客户」：最内层扫描整棵 `idx_customer_created` 10 万行、过滤后 2.5 万行；加 `(status, customer_id)` 后变为覆盖索引查找，7 次采样中位数 60.4ms → 5.23ms
5. 函数、列运算、前导通配符、一边没有索引的 `OR`、`!=` 都是全表扫描；只改写日期条件、没有以 `created_at` 开头的索引时仍是全表扫描，补上 `idx_created` 后是 `range`，返回行数相同（415）
6. 前导通配符但只取索引列时 `type = index`；前缀匹配 `range`；`OR` 两边都有索引时 `index_merge`（`sort_union`）
7. 低区分度的 `idx_status`：走索引与全表扫描都返回 25,000 行，中位数 15.1ms 与 19.8ms，同一量级
8. 联合索引 `(status, created_at)` 与 `(created_at, status)`：`key_len` 都是 71，`EXPLAIN ANALYZE` 与 Handler 的行数都是 9,222/9,223；InnoDB ICP 计数器显示引擎检查的索引记录为 9,223 与 36,899

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：mysql 8.4.11（`compose.yaml` 固定 digest），容器限制 2 CPU、2 GB，`innodb_buffer_pool_size = 512M`，不开启 binlog。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 启动容器，确定性生成 10 万行 `orders`（公式见 `schema/02-seed.sql`），`ANALYZE TABLE`
2. 开启 InnoDB 的 `module_icp` 计数器
3. 按五个索引阶段（原始两个索引、加 `idx_status_customer`、换成 `idx_created`、换成 `idx_status`、两种联合索引顺序）逐条执行查询：预热 3 次，保存 `EXPLAIN`（制表符分隔）、`EXPLAIN FORMAT=JSON`、Handler 计数、ICP 计数与 `EXPLAIN ANALYZE`（计时查询采样 7 次）
4. `scripts/summarize.py` 生成 `summary.md` 并断言

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/plans/<查询>/` | 每条查询的 `query.sql`、`explain.tsv`、`explain.json`、`explain-analyze.txt`、`handler.txt`、`icp.txt`、`indexes.txt`（部分） |
| `evidence/summary.md` | 所有查询的 type、key、key_len、估算行数、Extra、Handler 读取、ICP 检查与中位耗时 |
| `evidence/assertions.txt` | 断言结果 |
| `evidence/dataset.txt、container.txt` | 版本、数据分布与容器配置 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/`。

## 五、误差、限制与不能推出的结论

- 估算行数（`rows`）来自统计信息抽样，每次 `ANALYZE TABLE` 后可能不同：一天范围的过滤估算在多次运行中为 10,816—11,100，断言不依赖估算值。
- 耗时是热缓存下单机容器的结果，只用于比较量级。
- 数据由公式生成，与文章发布时的数据不同：分组后的客户数为 5,000（原文 1,250），日期范围查询为 415 行（原文 417）。

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
| 2026-09-24 | 迁入本仓库，确定性造数重跑；并入调优篇第三、四节 | 是：更新数字；调优篇第三节改为用 ICP 计数器说明两种列顺序的差别（`EXPLAIN ANALYZE` 行数相同） |
