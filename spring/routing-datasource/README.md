# 路由数据源在哪一刻选库

对应文章：[routing-datasource.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/routing-datasource.md)。

两个 H2 内存库分别代表主库与副本，各有一行写着自己的名字。路由数据源（AbstractRoutingDataSource）的路由键来自 ThreadLocal，或按当前事务是否只读决定；查询 `SELECT name FROM whoami` 就能看出落到了哪个库。

```bash
make verify     # 需要 JDK 21+、Maven
make evidence   # 重新采集 evidence/
make clean
```
