# 验证记录：JOIN 之后的结果粒度

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

MySQL 8.4.11。正确答案（单表手算）：订单金额合计 480，明细小计合计 480，已支付合计 430。

1. **一对多扇出**：订单连明细后 `SUM(o.amount)` 得到 880；连接结果 8 行，订单只有 5 笔。
2. **两个子表互相放大**：订单同时连明细和支付，9 行、4 笔订单（未支付的订单被内连接丢掉）；`SUM(p.amount)` 得 830，`SUM(i.qty * i.price)` 得 630。
3. **DISTINCT 修不了**：`SUM(DISTINCT o.amount)` 得 380，金额同为 100 的两笔订单被合并。
4. **先聚合再连接**：把明细、支付各自聚合到订单粒度后再连接，三个合计分别是 480、480、430。
5. **计数**：订单左连支付，`COUNT(*)`=6，`COUNT(p.id)`=5，`COUNT(DISTINCT o.id)`=5，`COUNT(DISTINCT p.order_id)`=4；SKU B 的明细行 3 行，含 B 的订单 2 笔。
6. **外连接后过滤**：`LEFT JOIN … WHERE o.status = 'PAID'` 只剩 2 个用户；条件放进 `ON` 得到 5 个用户，其中 3 个为 0。
7. **人均**：按订单行平均 112.5，按下过单的用户平均 150，按全部用户平均 90。
8. **NOT IN 与 NULL**：子查询含 NULL 时 `NOT IN` 返回 0 行；过滤掉 NULL 或改用 `NOT EXISTS` 返回 2 行。
9. **空集**：没有行时 `SUM` 为 NULL、`COUNT` 为 0；分母用 `NULLIF(…, 0)` 时比值为 NULL。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/mysql-version.txt`](evidence/mysql-version.txt)。

## 三、执行步骤

见 `scripts/verify.sh`。期望值在 `schema/expected.tsv` 中逐条给出算式。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 结果是确定的，没有随机性。
- 只在 MySQL 8.4.11 上执行。这些是 SQL 的语义而非 MySQL 的实现细节，但数值格式（小数位）与 `sql_mode` 相关的行为（除零）在其他数据库上不同，没有验证。
- 没有涉及性能：先聚合再连接在大表上的执行计划需要单独评估。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

脚本退出时删除 MySQL 容器与网络。

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，30 条查询全部与手算期望值一致 | 新文章，数字取自本次证据 |
