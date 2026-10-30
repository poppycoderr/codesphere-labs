# 值进库之前经过的转换

对应文章：[charset-collation-decimal.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/charset-collation-decimal.md)。

`scripts/verify.sh` 启动 labs 共享的 MySQL 8.4.11，以 utf8mb4 客户端执行 `schema/main.sql`，逐条执行 `schema/errors.sql` 里预期报错的语句，再分别用 utf8mb4 与 latin1 两种客户端字符集写入并读回同一个中文字符串，最后整理成结论行。

```bash
make verify     # 需要 Docker；约 30 秒
make evidence   # 重新采集 evidence/
make clean
```
