# 验证记录：用数据库表当队列：提交顺序、SKIP LOCKED 与租约

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| A 拿到 id=1 未提交、B 拿到 id=2 已提交 | 消费者第一次只看到 B，记下最大 id=2 |
| A 提交后按 id &gt; 2 拉取 | 看不到 A，id=1 永远不会被拉到 |
| 回滚 | id=3 回滚后下一条是 id=4，有空洞 |
| 按 `created_at` 拉取 | 同样漏掉晚提交的 A |
| 两个消费者 `FOR UPDATE SKIP LOCKED` 各认领 2 条 | w1 得到 [1, 2]，w2 得到 [3, 4]；未提交的 id=5 都没有认领到 |
| id=5 提交后再认领 | 得到 [5] |
| w1 崩溃、租约过期 | w4 重新认领 [1, 2]，w2 的行未被抢走 |
| w1 恢复后带 `locked_by` 条件完成 | 影响 0 行；w4 完成影响 1 行 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 交错由代码顺序固定，只覆盖两三个会话；高并发下 `SKIP LOCKED` 的吞吐、索引与锁等待没有测量。
- 租约过期用直接修改 `locked_until` 模拟，没有覆盖时钟误差；租约过期后同一任务可能被执行两次，任务本身要幂等。
- 只验证 MySQL 8.4.11；PostgreSQL 的 `SKIP LOCKED` 语义相近，但没有在本实验中验证。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-21 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
