# 读写锁与 StampedLock 的边界

对应文章：[aqs-and-locks.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/aqs-and-locks.md) 第六节。

`src/RwLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，场景由顺序执行或闩锁排定，输出是确定的：

1. `ReentrantReadWriteLock`：持有读锁时获取写锁（升级）、持有写锁时获取读锁（降级）；
2. 重入：`ReentrantReadWriteLock` 的写锁，与 `StampedLock` 持有写锁时再次获取；
3. `StampedLock` 乐观读：读到一半发生写入时的 `validate`、退回读锁重读、没有写入时；
4. `tryConvertToWriteLock`：只有一个读者、用旧 stamp 解锁、有两个读者；
5. 缓存加载：键 A 在全局写锁里加载时读键 B；`ConcurrentHashMap.computeIfAbsent` 加载键 A 时读键 B，以及两个线程同时请求键 A。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
