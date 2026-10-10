# SQL 注入的边界

对应文章：[sql-injection-boundaries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/sql-injection-boundaries.md)。

`src/InjectionLab.java` 通过 JDBC（Connector/J 9.7.0，默认连接参数）连接共享的 MySQL 8.4.11 容器，库里只有实验自己建的两张表：`products` 与 `accounts`，数据都是演示值。实验只针对这个本地容器。

覆盖：值的位置上拼接与绑定参数的差别（字符串、数值、`UNION`）；`ORDER BY` 的列名与方向绑定参数的结果；把排序参数拼进 SQL 后用 `CASE` 子查询做布尔盲注；白名单；绑定了参数的 `LIKE` 遇到 `%` 与 `_`；`IN (?)` 绑定逗号分隔的字符串；`allowMultiQueries`；先安全地存入、再读出来拼接的二次注入。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）。结束后 `make clean` 删除容器
make evidence
make clean
```
