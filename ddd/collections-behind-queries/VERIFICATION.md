# 验证记录：领域模型里的集合背后的查询

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 1000 人的场次，聚合方法里用 `registrations.size()` 检查名额后报名 | SQL 3 条，加载实体 1001 个 |
| 改用计数器检查名额 | SQL 4 条（多一条更新场次），加载实体 1 个 |
| 只往 `mappedBy` 的 List 里加一项 | SQL 2 条，加载实体 1 个（集合没有加载） |
| 同上，场次的 `@Version` | 不变（1 → 1）；同时修改计数器时 1 → 2 |
| 往 `mappedBy` 的 Set 里加一项 | SQL 3 条，加载实体 1001 个 |
| `select count(r)` 查询 | SQL 1 条，加载实体 0 个 |
| 50 个场次逐个读集合大小 | SQL 51 条 |
| 同样的遍历，`join fetch` | SQL 1 条 |
| `orphanRemoval` 的集合 `clear()`（1005 项） | SQL 1007 条，逐行删除 |
| 批量删除 50 个场次的报名 | SQL 1 条 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- SQL 条数来自 Hibernate 的统计，没有区分 SELECT 与 DML；没有开启 JDBC 批处理（`hibernate.jdbc.batch_size`），开启后删除的往返次数会减少，但每行仍各自加载和删除。
- 没有使用字节码增强。Hibernate 的 `@LazyCollection(EXTRA)` 在 6.x 中已废弃，本实验不涉及。
- 「根版本号不变」是 Hibernate 对 `mappedBy` 一侧集合的处理；由拥有方维护的集合（没有 `mappedBy`）修改时会递增版本号，本实验没有覆盖。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-16 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
