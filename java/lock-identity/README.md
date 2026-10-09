# 锁对象的身份

对应文章：[lock-identity.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/lock-identity.md)。

`src/LockLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。两类判断方法：

- **丢更新**：两个线程各做 20 万次「读、加一、写」，结果小于 40 万就是丢了更新。最多重复 5 轮，任何一轮丢了就记为丢。
- **是不是同一把锁**：一个线程拿着锁 A 不放，看另一个线程 300 毫秒内能不能进入锁 B 保护的同步块。

读写不变量与「先检查再添加」两个场景用闩锁强制了线程的交错顺序，结果是确定的。`javac` 的警告通过 `javax.tools` 在程序里编译一小段源码取得。

## 快速运行

```bash
make verify     # 需要 Docker；约 20 秒
make evidence
make clean
```
