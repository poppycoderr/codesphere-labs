# 跨分片分页：归并代价、单列游标与复合游标

对应文章：[cross-shard-pagination.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/cross-shard-pagination.md)。

用固定种子生成 4 个分片、每片 5000 行的数据（`created_at` 只到秒，大量重复，`id` 全局唯一），按 `created_at` 倒序、`id` 倒序分页，每页 20 行。比较 offset 归并、平均分摊 offset、两种单列游标、复合游标，以及翻页期间插入新数据的影响。纯计算模拟，不涉及数据库。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
