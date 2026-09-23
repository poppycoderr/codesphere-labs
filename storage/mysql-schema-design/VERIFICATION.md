# 验证记录：表设计三个细节

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 唯一索引 `(user_id, product_code, deleted_at)` 允许两条 `deleted_at IS NULL` 的记录同时存在
2. `deleted_id` 写法：退订时写入自身主键，两次退订后再开通只有一条 `deleted_id = 0`，再插入报 `Duplicate entry '1001-VIP-0'`
3. 函数索引写法：只对未删除的行建唯一约束，再插入报 `Duplicate entry '1001:VIP' for key 'service_record_c.uk_active'`
4. 100 万个 IPv4 地址查询 `10.1.0.0/16`：二进制 `BETWEEN` 与 `LIKE '10.1.%'` 结果相同，字符串 `BETWEEN` 少约三成；二进制还能查 `/20` 网段；`'10.1.100.1' < '10.1.99.1'` 按字符串比较成立
5. 两表重建后，二进制列上的索引小于字符串列上的索引
6. 同一行：1 个线程到 64 个线程吞吐不到 2 倍，16 与 64 线程下至少 99% 的更新经历锁等待；分散到 1000 行时 64 线程吞吐是同一行的 5 倍以上；64 线程随机选 4 个或 16 个桶时吞吐依次提高

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：mysql 8.4.11（`compose.yaml` 固定 digest，默认配置，开启 binlog），JDK 21（固定 digest），MySQL Connector/J 8.0.27。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 执行 `schema/01-soft-delete.sql`，并对两种正确写法各再插入一条有效记录
2. 用公式生成 100 万个互不相同的 `10.x.y.z` 地址（`x = n × 7919 mod 2^24` 拆成三个字节），分别写入字符串表与二进制表，`ALTER TABLE … FORCE` 重建后比较网段查询与索引大小
3. 在与 MySQL 共享网络的 JDK 容器中运行 `src/HotRow.java`：每个线程 400 次自动提交的扣减，统计耗时、最大延迟与 `Innodb_row_lock_waits` 增量

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/soft-delete*.txt` | 三种写法的执行结果与重复插入的报错 |
| `evidence/ip.txt` | 转换函数、网段查询结果、字符串比较与索引大小 |
| `evidence/hot-row.tsv` | 各线程数与写法下的吞吐、最大延迟与锁等待次数 |
| `evidence/server.txt` | 版本与持久化参数 |
| `evidence/assertions.txt` | 断言结果 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/`。

## 五、误差、限制与不能推出的结论

- 吞吐与刷盘速度强相关，只断言相对关系。
- 线程启动有先后，偶尔有一两次更新恰好不需要等锁，锁等待次数按 99% 断言。
- IP 地址由公式生成，漏掉的比例取决于地址分布；结论是「字符串范围查询不正确」，32% 只对应这份数据。
- 分桶时每次随机选桶，与按线程固定分配桶的效果不同。

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
| 2026-09-24 | 迁入本仓库，默认配置重跑；IP 地址改为公式生成并在比较前重建表 | 是：网段查询 676/461 → 3,916/2,672；索引 37.6/55.7MB → 16.5/26.6MB；热点行与分桶吞吐按新证据修正（16 桶 4.6 倍 → 2.4 倍） |
