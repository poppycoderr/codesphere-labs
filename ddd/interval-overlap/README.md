# 时间区间不重叠

对应文章：[interval-overlap.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/interval-overlap.md)。

`src/OverlapLab.java` 通过 JDBC 连接实验自己的 MySQL 8.4.11 与 PostgreSQL 18 容器（`compose.yaml`，不暴露宿主机端口）。规则：同一个成员在同一时间只能有一个值班。比较四种守法：唯一键 `(member_id, starts)`；应用里先查有没有重叠再插入；检查前先 `SELECT … FOR UPDATE` 锁住成员行；PostgreSQL 的排他约束 `EXCLUDE USING gist (member_id WITH =, period WITH &&)`。并发场景用两个事务：各自检查，等两者都检查完（或等待超时）后再插入。谁先拿到锁不确定，所以只统计成功与失败的个数。最后检查半开区间与闭区间首尾相接的差别。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）。结束后 `make clean` 删除容器
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
