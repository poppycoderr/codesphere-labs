# 测试数据的归属

对应文章：[test-data-ownership.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/test-data-ownership.md)。

`src/TestDataLab.java` 通过 JDBC 连接共享的 MySQL 8.4.11 容器。「测试 A」与「测试 B」各用一条连接，程序按固定顺序交替执行它们的准备、断言、清理步骤，所以每种互相影响都是确定的，不依赖并发时机。

场景：两个测试插入同一条固定数据；断言数整张表的行数；清理步骤按模式删除；上一次运行没有走到清理；用不提交的事务准备数据再回滚；每个测试进程一个库。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）。结束后 `make clean` 删除容器
make evidence
make clean
```
