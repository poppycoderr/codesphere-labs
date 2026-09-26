# 深分页与索引的写入代价

对应文章：[database-design-and-tuning.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/database-design-and-tuning.md) 第五、六节。

MySQL 8.4.11 容器 + JDK 21 容器运行单文件程序 `src/Pagination.java`：

1. 50 万行的 `orders` 表，在第 0、1 万、10 万、40 万行之后取 20 行：`ORDER BY id LIMIT offset, 20` 与 `WHERE id > ? ORDER BY id LIMIT 20`，比较实际读取的行数（会话级 `Handler_read_*` 增量）、耗时和结果；
2. 按 `(created_at, id)` 倒序翻 50 页：行构造器写法 `WHERE (created_at, id) < (?, ?)` 与展开写法 `WHERE created_at < ? OR (created_at = ? AND id < ?)`；
3. 同样写入 20 万行（每 2000 行一批），表上分别有 0、2、5 个二级索引，各 3 轮。

## 快速运行

```bash
make verify     # 需要 Docker；约 2 分钟
make evidence
make clean
```
