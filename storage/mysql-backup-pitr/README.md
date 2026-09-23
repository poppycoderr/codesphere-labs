# 误删恢复：全量逻辑备份 + binlog 按 GTID 重放，与对照实例比对业务校验和

对应文章：[mysql-backup-restore-and-pitr.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/mysql-backup-restore-and-pitr.md)。

订单、明细、库存、操作标记四张表的确定性业务流程：全量备份后继续写入，误删与合法写入并发发生。在隔离的恢复实例导入备份，用复制 SQL 线程重放归档的 binlog，`UNTIL SQL_BEFORE_GTIDS` 停在误删之前，以空事务跳过误删后补回之后的写入，与没有误删的对照实例比对业务校验和与不变量；另含备份损坏与 binlog 缺口两个失败路径。

按验证标准的十个部分记录，原始时间线与对照数据全部归档。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean      # 删除本实验的容器与数据卷
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
