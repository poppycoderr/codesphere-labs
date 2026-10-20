# 布隆过滤器的不存在与唯一约束

对应文章：[dedup-and-filter.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/dedup-and-filter.md)。

用标准库实现一个按目标误判率 1% 配置的布隆过滤器，配合内存中的 SQLite 表，验证「一定不存在」成立的前提：过滤器由快照构建后写路径没有同步、重建期间提前切换，以及两个并发请求都先查过滤器再插入时，唯一约束的作用。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
