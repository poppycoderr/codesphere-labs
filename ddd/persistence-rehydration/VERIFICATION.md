# 验证记录：持久化与重建

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **往返一致**：容量 5、已确认 5 人、候补 2 人的场次，保存后用新连接重建，业务状态（容量、两个名单及顺序）一致；重建后待发布事件 0 个，保存前是 7 个。
2. **重建不是创建**：上线「新场次容量至少 10」之后，历史场次（容量 5）经 `restore` 重建成功，事件 0 个；经 `open` 创建被拒绝；放宽规则后按 `register` 逐个重放，会再登记 7 个事件。
3. **版本号冲突**：两个请求都从版本 0 加载，A 写入 2 行（根表版本 + 1 行子表）并提交；B 的 `UPDATE ... WHERE version=0` 影响 0 行，抛出 `ConcurrentModification` 并回滚。最终版本 1，候补里只有 A 加入的 x，B 的子表写入一起回滚。
4. **子表的保存方式**：已有 1000 个参会人的场次新增 1 人，按差异保存写 2 行；整体替换（先删后插）写 2004 行（1 行根表、删除 1001 行、插入 1002 行），耗时约 60ms。
5. **继承映射**（90,000 张票，每种 30,000 张）：
   - 结果：三种映射对两个查询的结果行数相同（场次 42 的全部票种 300 行，价格高于 450 元的付费票 3018 行）。
   - 执行计划：单表是一次索引查找；每个具体类一张表的多态查询是 3 个分支的 `Append`（UNION ALL）；父表 + 子表的多态查询是两层 `Nested loop left join`，按子类型查询需要回父表取名称。
   - 耗时：在这个规模下都在 0.4—0.7ms 与 2.2—2.6ms 之间，差别不足以决定映射方式。
   - 空间（数据 + 索引）：单表 9.5 MB，每个具体类一张表 10.6 MB，父表 + 子表 11.6 MB。
   - 约束：单表用 `CHECK` 拒绝了没有价格的付费票；每个具体类一张表允许两张表出现同一个 id；父表 + 子表允许只有父行、没有价格子行的付费票。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/container.txt`](evidence/container.txt)。MySQL 8.4.11（2 CPU、1 GB，固定 digest），Connector/J 8.0.27（`rewriteBatchedStatements=true`）。

## 三、执行步骤

`scripts/verify.sh` 启动共享的 MySQL 容器、建表，在 JDK 21 容器中运行 `src/Persistence.java`，执行计划写入输出目录。查询耗时为预热 20 次后 100 次的平均值；空间取自 `ANALYZE TABLE` 之后的 `information_schema.tables`。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/output.tsv` | 五组对照的结果 |
| `evidence/inheritance-plans.txt` | 三种继承映射的 SQL 与 `EXPLAIN ANALYZE` |
| `evidence/container.txt` | 镜像与驱动版本 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 仓储用手写 JDBC，没有 ORM。JPA/MyBatis 的脏检查、级联和继承注解的具体行为不在本实验范围内。
- 「按差异保存」用加载时的参会人集合判断新增，没有处理删除与修改；真实实现需要完整的变更跟踪。
- 9 万张票的查询耗时差异很小，不能外推到更大规模或更复杂的多态查询；断言只检查结果行数与执行计划的形状。
- `CHECK` 约束在 MySQL 8.0.16 起强制执行；更早的版本会解析但忽略它。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
