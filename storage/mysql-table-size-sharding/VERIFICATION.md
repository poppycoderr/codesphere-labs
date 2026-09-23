# 验证记录：单表规模：B+ 树层高、扇出与大表 DDL

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 10 万行 `orders` 为 2 层；500 万行 `orders_big`、2,000 万行 `narrow_20m`、100 万行宽表 `wide_1m` 都是 3 层
2. 三张 3 层表的中间页扇出（叶子页数 ÷ 根页记录数）在 907—922 之间，与行宽无关
3. 按平均扇出推算，窄表（每页 440 行）3 层约可容纳 3.7 亿行，超过当前行数 10 倍以上；宽表（每页 6 行）约 500 万行
4. 页都在缓冲池中时，2 层与 3 层表的主键点查平均耗时相差不到 1 微秒；同一批主键第一次查询（每次约一次物理读）是热查询的约 8 倍
5. 2,000 万行窄表：`INSTANT` 加列为毫秒级，`INPLACE` 加二级索引十几秒，改列类型只能 `COPY` 且耗时是加索引的 2 倍以上

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：mysql 8.4.11（`compose.yaml` 固定 digest），容器限制 2 CPU、3 GB，`innodb_buffer_pool_size = 1G`，不开启 binlog，重启时不自动加载缓冲池。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 按主键顺序确定性生成四张表（`schema/02-seed.sql`）
2. 对每张表 `FLUSH TABLES … FOR EXPORT` 让页落盘，从 `INNODB_INDEXES` 查主键根页页号，用 `od` 读 `.ibd` 中根页的 `PAGE_LEVEL`（偏移 64）与 `PAGE_N_RECS`（偏移 54），从 `innodb_index_stats` 读叶子页数
3. 重启 MySQL，存储过程对每张表用同一批 20,000 个主键各查询两轮（冷、热），记录平均耗时与 `Innodb_buffer_pool_reads` 增量
4. 在窄表上依次执行 `INSTANT` 加列、`INPLACE` 加索引、`COPY` 改列类型与 `COUNT(*)`，并确认改列类型不支持 `INPLACE`

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/btree.tsv` | 四张表的行数、平均行长、根页页号、树高、根页记录数、叶子页数、每页行数与文件大小 |
| `evidence/point-lookups.tsv` | 冷、热两轮点查的平均耗时与物理读次数 |
| `evidence/ddl.tsv、ddl-inplace-rejected.txt` | 三类 DDL 与 `COUNT(*)` 的耗时，`INPLACE` 改列类型的报错 |
| `evidence/assertions.txt` | 断言与推算结果 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/`。

## 五、误差、限制与不能推出的结论

- 数据按主键顺序插入，页几乎填满；真实业务表的随机插入会让页只填一半多，同样行数需要更多页。
- 点查计时在存储过程内完成，不含网络与语句解析；冷读的物理读发生在本机 SSD 上，云盘的差距会更大。
- DDL 耗时与硬件有关，只说明数量级。

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
| 2026-09-24 | 迁入本仓库，确定性造数重跑；点查改为冷热两轮 | 是：窄表 33 → 38 字节、每页 499 → 440 行、容量 4.1 亿 → 3.7 亿；宽表每页 9 → 6 行、750 万 → 500 万；点查改为微秒级的冷热对比；DDL 11.7s/40.1s → 15.9s/52.3s |
