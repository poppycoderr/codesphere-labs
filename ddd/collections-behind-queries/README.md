# 领域模型里的集合背后的查询

对应文章：[collections-behind-queries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/collections-behind-queries.md)。

`src/CollectionLab.java` 用 Hibernate 原生 `Configuration` 启动（不经过 Spring），连接同一 compose 项目里的 PostgreSQL 18，依赖由 `pom.xml` 下载。场次 `Session` 有一个 `mappedBy` 的 `List<Registration>`（映射为 bag，`orphanRemoval = true`）和一个 `mappedBy` 的 `Set<Waitlist>`，以及计数器 `registeredCount` 和 `@Version`。每个场景在新的会话和事务里执行，前后用 Hibernate 统计（`generate_statistics`）记录执行的 SQL 条数（`getPrepareStatementCount`）和加载的实体数。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
