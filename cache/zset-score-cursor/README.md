# 有序集合按 score 分页：同分成员与复合游标

对应文章：[redis-command-execution-and-latency.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-command-execution-and-latency.md)。

在 Redis 8.10.1 的有序集合里放 14 个成员，其中 6 个 80 分、3 个 50 分。每页 4 个，按 score 倒序比较四种游标，以及翻页期间新增成员时按排名分页的结果。

## 快速运行

```bash
make verify     # 启动 Redis 容器，在 Python 3.14.8 客户端容器里用 redis-py 8.1.0 运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
