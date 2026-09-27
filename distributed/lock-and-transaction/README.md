# 锁与事务的边界、状态流转与主键类型

对应文章：[distributed-lock-in-practice.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/distributed-lock-in-practice.md)、[order-consistency.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/order-consistency.md)。

单文件程序 `src/LockAndTransaction.java`，连接共享的 MySQL 8.4.11（REPEATABLE READ）：

1. 200 个用户、每个用户 20 个线程同时下单，业务是「没有订单就插入一条」。按用户加一把 `ReentrantLock`（模拟工作正常的分布式锁），比较锁在事务提交前释放与包住整个事务；再在提交前释放的写法下给表加上 `user_id` 唯一键；
2. 1,000 笔待支付订单，「支付」和「超时关闭」两个线程逐笔处理，每一笔用 `CyclicBarrier` 同时开始，比较先查后改与条件更新；
3. 20 万行、每批 1,000 行，比较 `BIGINT` 自增、`BINARY(16)` 有序 UUID（`UUID_TO_BIN(UUID(), 1)`）、`CHAR(36)` 随机 UUID 三种主键的插入耗时与空间；表上另有订单号唯一索引和 `user_id` 普通索引。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3、网络可访问 Maven Central（首次下载 Connector/J）；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
