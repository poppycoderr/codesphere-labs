# codesphere-labs

[codesphere](https://github.com/poppycoderr/codesphere) 技术文章的伴生实验仓库，归档可运行示例、自动化测试、验证脚本、执行日志与性能证据，让文章中的关键结论可复现、可审查、可持续更新。

正文负责解释原理和取舍，这里负责回答「这个数字是怎么来的」：

```text
文章结论 → 示例场景 → 环境与数据 → 执行命令 → 原始输出 → 解释与边界
```

## 实验列表

| 实验 | 对应文章 | 类型 | 层级 |
|---|---|---|---|
| [design/notification-routing](design/notification-routing/) | 一个通知路由服务的演化 | **黄金样板**：设计模式与重构 | regular |
| [spring/aop-proxy-pitfalls](spring/aop-proxy-pitfalls/) | Spring AOP 为什么会失效 | **黄金样板**：真实 Spring 容器 | regular |
| [storage/order-by-limit-index-choice](storage/order-by-limit-index-choice/) | SQL 调优实战：优化器为什么放着好索引不用 | **黄金样板**：数据库性能 | performance |
| [design/separation-of-concerns](design/separation-of-concerns/) | 关注点分离与可测试性 | 轻量 | regular-docker |
| [design/reading-software-design](design/reading-software-design/) | 读懂一个系统的设计 | 轻量 | regular-docker |
| [design/encapsulation-composition-polymorphism](design/encapsulation-composition-polymorphism/) | 封装、组合与多态 | 轻量 | regular |
| [design/functional-design](design/functional-design/) | Java 里的函数式设计 | 轻量 | regular |
| [design/api-evolution](design/api-evolution/) | API 设计看错误何时暴露 | 轻量 | regular |
| [design/jdk-api-facts](design/jdk-api-facts/) | 设计原则、设计模式决策图 | 轻量 | regular |
| [design/aggregate-concurrency](design/aggregate-concurrency/) | 从统一语言到限界上下文 | 轻量 | regular-docker |
| [design/object-creation-lifecycle](design/object-creation-lifecycle/) | 对象创建与生命周期 | 轻量：Spring 容器 | regular |
| [design/wrapper-patterns](design/wrapper-patterns/) | 适配器、装饰器与代理 | 轻量 | regular |
| [design/extensible-processing-pipeline](design/extensible-processing-pipeline/) | 策略、回调与责任链 | 轻量 | regular |
| [design/local-event-delivery](design/local-event-delivery/) | 观察者、事件与消息 | 轻量：Spring 容器与事务同步 | regular |
| [design/registration-state-machine](design/registration-state-machine/) | 状态机与工作流 | 轻量 | regular |
| [design/adaptive-rate-limiter](design/adaptive-rate-limiter/) | 可复用的服务端组件：限流 | 组件：Redis 与故障注入 | regular-docker |
| [design/idempotency-key](design/idempotency-key/) | 可复用的服务端组件：幂等键 | 组件：MySQL、租约与崩溃窗口 | regular-docker |
| [design/progressive-delivery-rules](design/progressive-delivery-rules/) | 可复用的服务端组件：渐进发布 | 组件：本地规则引擎 | regular |
| [storage/innodb-locking-ranges](storage/innodb-locking-ranges/) | InnoDB 行锁锁的是什么 | 轻量 | regular-docker |
| [storage/mysql-explain-plans](storage/mysql-explain-plans/) | 读懂 Explain；SQL 调优实战（第三、四节） | 轻量 | regular-docker |
| [storage/mysql-mvcc-isolation](storage/mysql-mvcc-isolation/) | InnoDB MVCC 与隔离级别 | 轻量 | regular-docker |
| [storage/mysql-redo-undo-recovery](storage/mysql-redo-undo-recovery/) | Redo Log 与 Undo Log | 轻量 | regular-docker |
| [storage/mysql-large-table-cleanup](storage/mysql-large-table-cleanup/) | 千万级大表怎么清理数据 | 轻量 | performance |
| [storage/mysql-table-size-sharding](storage/mysql-table-size-sharding/) | 单表多大该拆分 | 轻量 | performance |
| [storage/mysql-bulk-import](storage/mysql-bulk-import/) | 百万行数据导入 | 轻量 | regular-docker |
| [storage/mysql-schema-design](storage/mysql-schema-design/) | 表设计里的三个细节 | 轻量 | regular-docker |
| [storage/mysql-oltp-olap](storage/mysql-oltp-olap/) | OLTP 与 OLAP | 轻量 | performance |
| [storage/mysql-replication-lag](storage/mysql-replication-lag/) | MySQL 复制与延迟 | 多节点：1 source + 2 replicas | performance |
| [storage/mysql-failover-read-consistency](storage/mysql-failover-read-consistency/) | MySQL 故障切换 | 多节点：故障注入与切换时间线 | performance |
| [storage/mysql-backup-pitr](storage/mysql-backup-pitr/) | MySQL 误删恢复 | 多实例：隔离恢复与对照校验 | regular-docker |
| [java/aqs-and-locks](java/aqs-and-locks/) | 从 AQS 看 ReentrantLock | 轻量 | regular |
| [cache/redis-data-structures-memory](cache/redis-data-structures-memory/) | Redis 数据结构与编码 | 轻量 | regular-docker |
| [cache/redis-memory-eviction](cache/redis-memory-eviction/) | Redis 内存满了会怎样 | 轻量 | regular-docker |
| [cache/redis-distributed-semaphore](cache/redis-distributed-semaphore/) | 基于 Redis 的分布式信号量 | 轻量 | regular-docker |
| [cache/redis-persistence-recovery](cache/redis-persistence-recovery/) | Redis 持久化与恢复 | 故障注入：进程崩溃与断电（LazyFS） | performance |
| [cache/redis-sentinel-failover](cache/redis-sentinel-failover/) | Redis Sentinel 故障切换 | 多节点：1 primary + 2 replicas + 3 Sentinel | performance |
| [cache/redis-latency-diagnostics](cache/redis-latency-diagnostics/) | Redis 为什么突然变慢 | 探测延迟与故障注入 | performance |
| [cache/redis-cluster-resharding](cache/redis-cluster-resharding/) | Redis Cluster 的应用契约 | 多节点：3 primary + 3 replicas | performance |
| [cache/redis-atomicity-and-locks](cache/redis-atomicity-and-locks/) | Redis 原子性边界 | 轻量 | regular-docker |

**黄金样板**按 [验证标准](docs/verification-standard.md) 的完整要求建设，用来确定同类实验的格式；**轻量实验**只保留核心代码、一条验证入口、原始输出和简要验证记录。

需要启动 Spring Boot、组合多个中间件的项目级验证（事务传播、Kafka 投递语义等），将在下一阶段用 Spring Boot 4 + Testcontainers 统一建设，目前还没有对应目录。

## 快速开始

```bash
make list                                   # 列出实验
make verify EXP=design/notification-routing # 运行单个实验并断言关键结论
make check                                  # 目录约定、元数据、脚本语法、敏感信息扫描
```

各实验的依赖写在自己的 README 中。常见要求：JDK 21+；Spring 实验需要 Maven 3.9+；带容器的实验需要 Docker（Compose v2）与 Python 3（PyYAML 仅用于 `make check`）。

## 目录约定

```text
<专题>/<实验>/
├── README.md          解决什么问题、对应哪篇文章、怎么运行
├── experiment.yaml    机器可读的元数据
├── Makefile           verify / evidence / clean
├── scripts/verify.sh  一条命令完成核心验证与断言
├── src/ tests/ ...    最小可运行代码
├── evidence/          原始输出（make evidence 生成）
└── VERIFICATION.md    结论、环境、步骤、证据索引、限制、历史
```

顶层专题名与 codesphere 的 `docs/<专题>/` 一致。规范见：

- [docs/verification-standard.md](docs/verification-standard.md)：验证标准与完成定义
- [docs/evidence-format.md](docs/evidence-format.md)：证据格式、元数据字段、规范化规则
- [docs/environment-support.md](docs/environment-support.md)：支持的环境与 CI 层级
- [docs/article-integration.md](docs/article-integration.md)：文章与实验的同步流程

## 文章如何引用

文章末尾的「配套实验」链接到固定 commit，而不是默认分支：

```text
https://github.com/poppycoderr/codesphere-labs/tree/<commit>/<专题>/<实验>
```

默认分支会继续变化，固定 commit 才能还原文章发布时的证据。

## 许可证

代码与脚本使用 [Apache License 2.0](LICENSE)。文章正文的许可见 codesphere 仓库。
