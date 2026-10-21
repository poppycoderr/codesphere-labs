# 交付周期的口径：事件历史、等待与未完成项

对应文章：[delivery-flow-metrics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/delivery-flow-metrics.md)。

7 个工作项的合成状态事件（时间单位为小时），覆盖重复投递、乱序到达、日内重开、取消、未完成与从未开始。从事件历史重建每个工作项的状态序列，比较不同起点定义、重开处理、每日快照、活动与等待、只统计已完成项、按到达或发生时刻排序的结果。数据是合成的，只用来说明口径对数字的影响。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
