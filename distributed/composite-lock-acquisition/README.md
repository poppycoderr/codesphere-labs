# 组合获取多把锁

对应文章：[composite-lock-acquisition.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/composite-lock-acquisition.md)。

`src/Lab.java` 用一个可控时钟的内存租约锁服务模拟多个节点，用 ReentrantLock 演示加锁顺序，逐项验证组合获取的失败路径：部分成功的回退、回退失败、相反顺序的互相等待、租期预算、多数派在节点丢失状态时的双持有者。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
