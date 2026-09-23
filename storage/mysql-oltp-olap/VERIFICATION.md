# 验证记录：OLTP 与 OLAP：MySQL 与 ClickHouse 的三条查询

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 同一公式生成的 500 万行在两边执行三条查询，结果逐字节相同
2. 全表聚合与范围聚合：MySQL（数据全部在缓冲池中）比 ClickHouse 慢 20 倍以上（实测约 37 倍与 53 倍）
3. 按客户查最近 10 单：MySQL 走 `(customer_id, created_at)` 索引约 0.3ms；ClickHouse 约 7ms，并读取了约 110 万行

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：mysql 8.4.11、clickhouse 25.8.33.6（`compose.yaml` 均固定 digest），两个容器各限制 2 CPU、3 GB；MySQL `innodb_buffer_pool_size = 2G`，ClickHouse 为默认配置。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. MySQL 与 ClickHouse 各按 `schema/` 中相同的公式生成 500 万行（ClickHouse 的金额按整数分精确构造，与 MySQL 的 `ROUND` 结果一致）
2. 预热 MySQL 缓冲池
3. 每条查询：两边各执行一次保存结果（ClickHouse 保留 Decimal 末尾的 0），比对是否相同；各预热 3 次、采样 7 次
4. MySQL 在同一会话中用 `NOW(6)` 前后相减计时，并保存 `EXPLAIN ANALYZE`；ClickHouse 从 `system.query_log` 取耗时、读取行数与字节数
5. 记录两边的存储占用与 ClickHouse 各列的压缩大小

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/queries/<查询>/` | 两边的 SQL、结果、MySQL 每次采样耗时与 `EXPLAIN ANALYZE`、ClickHouse 的 query_log 记录 |
| `evidence/summary.md` | 中位数、范围、读取量与结果是否一致 |
| `evidence/storage.txt` | 存储占用与各列压缩大小 |
| `evidence/container.txt` | 版本、镜像与资源限制 |
| `evidence/assertions.txt` | 断言结果 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/`。

## 五、误差、限制与不能推出的结论

- 两边都只有 2 个 CPU；文章发布时 ClickHouse 使用了整机 6 核，聚合的差距因此从约 100 倍变为约 37—53 倍。
- 数据由公式生成，备注列高度重复，压缩率远好于真实数据；`status` 列在这份数据里是打散的，压缩后约 11MB（文章原稿 37.8KB 来自不同的数据分布）。
- 结果只用于说明数量级与方向，不代表两个产品在生产环境中的性能对比。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 在同一台机器的容器中实测，数字见文章 | — |
| 2026-09-24 | 迁入本仓库，两边限制相同资源并比对查询结果 | 是：聚合约 100 倍 → 37—53 倍；读取量、存储占用与 status 列压缩大小按新证据修正 |
