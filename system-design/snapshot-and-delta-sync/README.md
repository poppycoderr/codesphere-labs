# 快照加增量的同步

对应文章：[snapshot-and-delta-sync.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/snapshot-and-delta-sync.md)。

`src/SyncLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。来源维护一份「键 → 库存」的状态，每次修改产生一条带连续序号的增量（「某个键加减多少」）；副本从一份快照起步再应用增量。来源、网络与副本都在一个线程里模拟，输出是确定的。

1. 先取快照、后订阅，两步之间来源发生了修改；
2. 先订阅并缓存、后取快照：缓存的增量全部应用，与按序号丢弃；
3. 一条增量重复投递、一条丢失：不检查序号，与检查序号后重新取快照；
4. 乱序到达，副本最多暂存 2 条；
5. 副本只保存库存最多的前 2 个键，随后其中一个售罄。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
