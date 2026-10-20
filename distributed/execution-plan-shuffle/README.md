# Spark 物理计划、倾斜分区与加盐结果等价性

对应文章：[docs/distributed/execution-plan-shuffle.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/execution-plan-shuffle.md)。

过滤投影计划没有 Exchange，groupBy count 有 Exchange 和局部聚合。10,000 行中 8,000 行为热点键，按 key 分区最大 8,460 行；按 key、id%16 分区后最大 4,090 行，二次计数合并逐键等价。新 DataFrame 未自动缓存，cache/count 后有内存扫描，再 unpersist。事件日志确认发生实际 shuffle。

## 运行

```bash
make verify
make evidence
make clean
```

需要 Docker；首次拉取和依赖下载耗时不固定。脚本只使用本实验的文件和容器，不修改宿主机磁盘或外部服务。环境、证据与限制见 [VERIFICATION.md](VERIFICATION.md)。
