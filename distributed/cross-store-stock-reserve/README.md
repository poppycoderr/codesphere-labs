# 跨存储库存预扣：Redis 预扣、MySQL 扣减与对账

对应文章：[cross-store-stock-reserve.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/cross-store-stock-reserve.md)。

`compose.yaml` 启动固定 digest 的 Redis 8.10.1 与 MySQL 8.4.11；`src/StockReserve.java`（单文件，最小 RESP 客户端 + JDBC）先跑朴素写法的三个场景，再跑修正写法：Lua 预占（`hold:{订单号}`）、数据库扣减与流水同事务、提交后删除预占；数据库明确拒绝时立即释放，提交结果未知时保留预占交给对账；取消先在数据库把流水改为 CANCELLED，再按订单号在 Redis 加回一次。提交失败用另一条连接 `KILL` 注入，崩溃用抛异常模拟，每个场景后检查「初始 = 数据库可用 + 已售、Redis = 数据库可用、没有遗留预占」。

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
