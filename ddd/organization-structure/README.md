# 组织结构的知识层与实例层

对应文章：[organization-structure.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/organization-structure.md)。

`src/OrgLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，连接同一 compose 项目里的 PostgreSQL 18。知识层是两张表：结构线（行政线 LINE、职能线 FUNCTION）与允许的上下级类型 `allowed_parent`；实例层是组织 `org_unit` 与带有效期（`daterange`）的上下级关系 `org_link`，`org_link` 上有排他约束 `EXCLUDE (child WITH =, structure WITH =, valid WITH &&)`。应用层挂接时先按知识层检查类型，再用递归查询检查会不会成环。并发场景里两个事务在都完成检查之后才插入，交错由闩锁固定。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒（不含拉取镜像）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
