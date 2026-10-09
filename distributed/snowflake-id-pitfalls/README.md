# 雪花算法式 ID 的前提

对应文章：[snowflake-id-pitfalls.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/snowflake-id-pitfalls.md)。

`src/SnowflakeLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。它实现了两个生成器：没有任何保护的写法，和带回拨检查、序列号用完时等待下一毫秒的写法。ID 的布局是 41 位毫秒时间戳、10 位机器号、12 位序列号。时钟由测试代码给出，各种情况按固定顺序构造，输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。

1. 运行中时钟回拨 5 毫秒：无保护与带检查的生成器；
2. 进程重启期间时钟回退：回拨检查只在内存里与恢复了持久化的时间戳；
3. 同一毫秒内生成 5000 个；
4. 两个实例用同一个机器号；机器号取 IP 低 10 位的例子；把主机名哈希成机器号时的撞号概率；
5. 两个实例时钟相差 3 毫秒时 ID 的大小与生成先后；
6. 时间戳位数的寿命、ID 超过 2^53 的时间、单机器号的速率上限。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
