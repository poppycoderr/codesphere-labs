# Redis 原子性、事务与锁

对应文章：[redis-atomicity-transactions-and-locks.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-atomicity-transactions-and-locks.md)。

单节点 Redis 8.10.1（开启 AOF）。`src/Atomicity.java` 自带最小 RESP 客户端，以「活动名额 100 个、50 个线程各抢 10 次」为统一案例：

- 名额扣减的五种写法：`GET` 判断后 `SET`、`DECR` 后补偿、`WATCH` + `MULTI` 重试、Lua（`EVALSHA`）、Function（`FCALL`）；
- MULTI 的入队错误、执行时错误、`DISCARD` 与 `WATCH` 冲突；
- 重启前后的 `EVALSHA` 与 `FCALL`；
- 锁：`SET NX PX`、不校验 token 的 `DEL`、比较 token 的释放；
- 租约 500ms、持有者停顿 1500ms：没有 fencing 与有 fencing token 时下游的最终值（下游用一段 Lua 模拟条件更新）。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）与 Python 3；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口。
