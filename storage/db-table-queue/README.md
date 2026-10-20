# 用数据库表当队列：提交顺序、SKIP LOCKED 与租约

对应文章：[db-table-queue.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/db-table-queue.md)。

在 MySQL 8.4.11（默认 `innodb_autoinc_lock_mode=2`、REPEATABLE READ）上用多个连接固定交错顺序，验证：按自增 id 或插入时间做「上次读到哪」的游标会漏掉晚提交的行；按状态用 `FOR UPDATE SKIP LOCKED` 认领；租约过期后重新认领与旧持有者的完成更新。

## 快速运行

```bash
make verify     # 启动 MySQL 容器，在 Python 3.14.8 客户端容器里用 PyMySQL 1.2.3 运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
