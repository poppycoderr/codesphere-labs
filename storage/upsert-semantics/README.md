# 冲突时到底做了什么：INSERT IGNORE、ON DUPLICATE KEY UPDATE 与 REPLACE

对应文章：[upsert-semantics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/upsert-semantics.md)。

`schema/upsert.sql` 建 `users`（`email`、`phone` 各有唯一键，`nickname VARCHAR(10) NOT NULL`）、带 `ON DELETE CASCADE` 外键的 `order_items` 与记录删除的 `AFTER DELETE` 触发器，依次执行各种冲突写入，用 `ROW_COUNT()` 与 `GET DIAGNOSTICS` 记录影响行数和第一条警告；`scripts/verify.sh` 把结果整理成结论行并与预期比较。

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence   # 重新采集 evidence/
make clean
```
