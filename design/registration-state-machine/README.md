# 报名状态机

对应文章：[state-machines-and-workflows.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/state-machines-and-workflows.md)。

单文件 `src/StateMachine.java`，活动报名有 5 个状态（PENDING、WAITLISTED、CONFIRMED、CANCELLED、CHECKED_IN）和 5 个命令：

- 把随需求逐步加上的条件分支与集中的转换表逐个组合对比；
- 从 PENDING 出发的可达性与终态；
- 1000 轮 CANCEL 与 CHECK_IN 并发：读出、判断、写回之间有一次 0.2ms 的存储往返，对比不带版本号与带版本号的比较并交换；
- 确认后发通知：状态改完直接调用通知服务，与写入待发送记录后由投递器重试。

数据库上的条件更新（`UPDATE … WHERE status = ?`）实测见 [design/aggregate-concurrency](../aggregate-concurrency/)。

## 快速运行

```bash
make verify     # 需要 JDK 21
make evidence   # 重新采集 evidence/
make clean      # 清理本实验的构建产物
```
