# 脏标记与版本化快照

对应文章：[versioned-snapshot-persistence.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/versioned-snapshot-persistence.md)。

`src/SnapshotLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。内存里有一份可修改的状态（两个字段之和恒为 100，外加一个备注），后台把它保存到一个可注入失败与阻塞点的内存存储。场景由闩锁排定，结果是确定的：

1. 先清脏标记再保存，第一次保存失败；
2. 保存成功后再清脏标记，保存期间发生了新的修改；
3. 保存线程逐字段读取可变对象，读到一半时发生修改；
4. 版本化的不可变快照：保存失败、保存期间发生新的修改；
5. 两次写入乱序到达存储：存储不比较版本，与按版本条件写入。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
