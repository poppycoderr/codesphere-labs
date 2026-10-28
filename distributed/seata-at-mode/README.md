# Seata AT 模式：分支已经提交的全局事务

对应文章：[seata-at-mode.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/seata-at-mode.md)。

`compose.yaml` 启动固定 digest 的 MySQL 8.4.11（`schema/setup.sql` 建账户表与 `undo_log`）与 Seata Server 2.6.0（file 存储模式）；`pom.xml` 只用来解析客户端依赖（`seata-all` 2.6.0、Jackson、MySQL 驱动），`src/SeataAt.java` 用 TM API 开启全局事务，经 `DataSourceProxy` 执行分支，另用不经 Seata 的连接模拟其他写入方，客户端配置见 `config/`。依次验证：全局提交前的可见性、全局回滚、绕过 Seata 的写入后回滚、全局锁、全局事务超时。

```bash
make verify     # 需要 Docker；约 2 分钟（不含首次拉取镜像与依赖）
make evidence   # 重新采集 evidence/
make clean
```
