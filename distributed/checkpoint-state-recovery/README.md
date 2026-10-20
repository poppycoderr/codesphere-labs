# Flink checkpoint 后的单机算子状态恢复

对应文章：[docs/distributed/checkpoint-and-result-time.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/checkpoint-and-result-time.md)。

DataGeneratorSource 生成 1—200，首次在 120 前故意失败；重启后的第一条记录读到非零 ValueState，且恢复值正好等于该记录序号减 1（状态与数据源位置来自同一个 checkpoint），最终计数 200。恢复点随 checkpoint 时序变化（已观测到 115—119），只断言两者的关系，不断言固定编号。

## 运行

```bash
make verify
make evidence
make clean
```

需要 Docker；首次拉取和依赖下载耗时不固定。脚本只使用本实验的文件和容器，不修改宿主机磁盘或外部服务。环境、证据与限制见 [VERIFICATION.md](VERIFICATION.md)。
