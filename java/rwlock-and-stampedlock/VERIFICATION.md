# 验证记录：读写锁与 StampedLock 的边界

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 25.0.4。

1. `ReentrantReadWriteLock` 持有读锁时 `tryLock` 写锁返回 false；持有写锁时 `tryLock` 读锁返回 true，释放写锁后仍持有读锁 1 次。
2. `ReentrantReadWriteLock` 同一线程第二次获取写锁成功，持有 2 次；`StampedLock` 同一线程持有写锁时 `tryWriteLock` 与 `tryReadLock` 都返回 0。
3. 乐观读期间发生写入：读到 `x=60 y=70`（和为 130，数据的和恒为 100），`validate` 返回 false；退回读锁重读得到 `x=30 y=70`。没有写入时 `validate` 返回 true，全程没有获取锁。
4. 只有一个读者时 `tryConvertToWriteLock` 成功；之后用旧的读 stamp 解锁抛 `IllegalMonitorStateException`，写锁仍被持有；用新 stamp 解锁后释放。有两个读者时转换返回 0。
5. 键 A 在全局写锁里加载时，读键 B 在 100ms 内拿不到读锁。`ConcurrentHashMap.computeIfAbsent` 加载键 A 期间读键 B 没有被阻塞；两个线程同时请求键 A，加载函数执行 1 次。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/RwLab.java`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 没有做任何性能比较。乐观读是否更快取决于读写比例与临界区长度，需要在目标负载上测。
- 乐观读的场景在单线程里用「读一半、写、再读」排出来，说明校验的作用，不说明冲突出现的频率。
- `computeIfAbsent` 加载期间不阻塞键 B，依赖两个键落在哈希表的不同桶里；同一个桶里的其他键会被阻塞。
- 没有覆盖 `StampedLock` 被中断时的行为和它不支持 `Condition` 的限制。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，输出与预期逐行一致 | AQS 一文新增第六节，结论取自本次证据 |
