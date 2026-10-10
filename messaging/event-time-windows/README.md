# 事件时间与窗口

对应文章：[event-time-windows.md](https://github.com/poppycoderr/codesphere/blob/master/docs/messaging/event-time-windows.md)。

`src/EventTimeLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，用 Kafka Streams 4.3.1 自带的 `TopologyTestDriver` 驱动一个「按一分钟滚动窗口计数」的拓扑，不需要 broker。每个事件有发生时刻与到达时刻，按到达顺序送入；记录的时间戳用发生时刻（按事件时间归窗）或到达时刻（模拟按处理时间归窗）。状态存储用内存实现，关闭记录缓存，所以每次更新都有输出，取每个窗口最后一次输出的计数。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 20 秒
make evidence
make clean
```
