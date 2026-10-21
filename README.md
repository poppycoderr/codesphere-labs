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
| [design/shared-flow-extension-points](design/shared-flow-extension-points/) | 多个产品共用一套流程：骨架、扩展点与契约测试 | 轻量：确定性输出 | regular-docker |
| [design/component-dependencies](design/component-dependencies/) | 包之间的依赖：环、端口与 common 包 | 轻量：确定性输出 | regular-docker |
| [design/di-container-identity](design/di-container-identity/) | 依赖注入容器的绑定身份：Class 与类型加限定符、路径栈与已访问集合、延迟边、类型引用相等性 | 已验证 | regular-docker |
| [design/scope-cache-ownership](design/scope-cache-ownership/) | 单例作用域的缓存所有权：浅复制共享缓存、别名、并发双重创建、null 歧义、失败重试、依赖元数据转发 | 已验证 | regular-docker |
| [design/jakarta-rest-behavior](design/jakarta-rest-behavior/) | Jakarta REST 的默认行为：返回值与状态码、HEAD/OPTIONS、异常与映射器、参数缺省与转换失败 | 已验证 | regular-docker |
| [design/declarative-http-client](design/declarative-http-client/) | 声明式 HTTP 客户端：代理、参数名、路径与查询编码、错误状态、超时、默认方法，对照手写代理 | 已验证 | regular-docker |
| [design/polyline-edit-session](design/polyline-edit-session/) | 折线编辑会话：预览、提交、撤销与外部更新 | 标准：真实 Chromium，确定性输出 | regular-docker |
| [refactoring/rename-boundaries](refactoring/rename-boundaries/) | 名字说的是什么：动作、角色与改名的边界 | 轻量：确定性输出（Jackson 3.2.3） | regular-docker |
| [refactoring/duplication-and-extraction](refactoring/duplication-and-extraction/) | 抽一个公共方法之前：同一条知识，还是只是长得像 | 轻量：确定性输出 | regular-docker |
| [refactoring/behavior-preserving-extraction](refactoring/behavior-preserving-extraction/) | 把长方法拆开，哪些行为最容易被改掉 | 轻量：确定性输出 | regular-docker |
| [refactoring/parameter-placement](refactoring/parameter-placement/) | 参数放在哪里：请求数据、依赖与上下文 | 轻量：确定性输出 | regular-docker |
| [refactoring/control-flow-complexity](refactoring/control-flow-complexity/) | 嵌套、卫语句、switch 与多态：复杂度到底降了没有 | 轻量：确定性输出（PMD 7.28.0） | regular-docker |
| [refactoring/local-state-semantics](refactoring/local-state-semantics/) | 换一种写法，改了哪些契约：局部变量、final 与不可变集合 | 轻量：确定性输出 | regular-docker |
| [ddd/registration-acceptance-tests](ddd/registration-acceptance-tests/) | 事件风暴；领域建模 | 轻量：规则表与验收测试 | regular |
| [ddd/entities-and-value-objects](ddd/entities-and-value-objects/) | 实体与值对象 | 轻量 | regular |
| [ddd/aggregate-boundaries](ddd/aggregate-boundaries/) | 聚合边界 | 轻量：MySQL 并发 | regular-docker |
| [ddd/layered-architecture](ddd/layered-architecture/) | 分层、应用服务与仓储 | 轻量：ArchUnit | regular |
| [ddd/deployment-boundaries](ddd/deployment-boundaries/) | 从模型到部署单元 | 轻量：同进程与 HTTP 拆分 | regular |
| [ddd/persistence-rehydration](ddd/persistence-rehydration/) | 持久化与重建 | 轻量：MySQL 映射与执行计划 | regular-docker |
| [ddd/context-integration-outbox](ddd/context-integration-outbox/) | 上下文集成 | 组件：outbox、去重、乱序与死信 | regular-docker |
| [ddd/cqrs-read-models](ddd/cqrs-read-models/) | CQRS 读模型 | 轻量：MySQL 读模型与投影 | regular-docker |
| [ddd/legacy-modernization-slice](ddd/legacy-modernization-slice/) | 遗留系统的精益切片 | 轻量：字符化与影子比对 | regular |
| [ddd/invariant-entry-points](ddd/invariant-entry-points/) | 构造器里写了校验，对象就一定合法吗 | 轻量：确定性输出（Jackson 3.2.3） | regular-docker |
| [ddd/interval-overlap](ddd/interval-overlap/) | 按键唯一不等于按时间不重叠 | 标准：MySQL 8.4 与 PostgreSQL 18，确定性输出 | regular-docker |
| [ddd/command-and-identity](ddd/command-and-identity/) | 请求里的租户 ID 能不能信：请求、身份与命令 | 轻量：确定性输出（Spring MVC 7.0.9 MockMvc） | regular-docker |
| [ddd/planned-occurred-recorded](ddd/planned-occurred-recorded/) | 计划时刻、发生时刻与记录时刻 | 轻量：确定性输出 | regular-docker |
| [ddd/subtype-or-type-field](ddd/subtype-or-type-field/) | 子类型、类型字段与跨表身份 | 常规：PostgreSQL 容器 | regular-docker |
| [ddd/organization-structure](ddd/organization-structure/) | 组织结构的知识层、有效期与并发成环 | 常规：PostgreSQL 容器 | regular-docker |
| [ddd/collections-behind-queries](ddd/collections-behind-queries/) | 领域模型里的集合背后的查询 | 常规：PostgreSQL 容器 | regular-docker |
| [ddd/roles-of-a-party](ddd/roles-of-a-party/) | 同一个人的几种角色 | 轻量：确定性输出 | regular-docker |
| [ddd/data-provenance-snapshots](ddd/data-provenance-snapshots/) | 金额的来历：快照、价格版本与凭证链 | 常规：PostgreSQL 容器 | regular-docker |
| [ddd/model-to-http-api](ddd/model-to-http-api/) | 从模型到 HTTP 接口：条件更新、命令与受理 | 轻量：确定性输出 | regular-docker |
| [ddd/script-or-domain-model](ddd/script-or-domain-model/) | 事务脚本还是领域模型：三次需求变化的代码演进 | 轻量：确定性输出 | regular-docker |
| [ddd/candidate-confirmation-activation](ddd/candidate-confirmation-activation/) | 候选、确认与生效：抽取结果与模型发布 | 轻量：确定性输出（SQLite） | regular-docker |
| [storage/innodb-locking-ranges](storage/innodb-locking-ranges/) | InnoDB 行锁锁的是什么 | 轻量 | regular-docker |
| [storage/mysql-explain-plans](storage/mysql-explain-plans/) | 读懂 Explain；SQL 调优实战（第三、四节） | 轻量 | regular-docker |
| [storage/mysql-mvcc-isolation](storage/mysql-mvcc-isolation/) | InnoDB MVCC 与隔离级别 | 轻量 | regular-docker |
| [storage/mysql-redo-undo-recovery](storage/mysql-redo-undo-recovery/) | Redo Log 与 Undo Log | 轻量 | regular-docker |
| [storage/mysql-large-table-cleanup](storage/mysql-large-table-cleanup/) | 千万级大表怎么清理数据 | 轻量 | performance |
| [storage/mysql-table-size-sharding](storage/mysql-table-size-sharding/) | 单表多大该拆分 | 轻量 | performance |
| [storage/mysql-bulk-import](storage/mysql-bulk-import/) | 百万行数据导入 | 轻量 | regular-docker |
| [storage/excel-import-memory](storage/excel-import-memory/) | 百万行数据导入（读取端） | 轻量：POI 与 Fesod，需约 8 GB 内存 | performance |
| [storage/mysql-schema-design](storage/mysql-schema-design/) | 表设计里的三个细节 | 轻量 | regular-docker |
| [storage/mysql-oltp-olap](storage/mysql-oltp-olap/) | OLTP 与 OLAP | 轻量 | performance |
| [storage/mysql-replication-lag](storage/mysql-replication-lag/) | MySQL 复制与延迟 | 多节点：1 source + 2 replicas | performance |
| [storage/mysql-failover-read-consistency](storage/mysql-failover-read-consistency/) | MySQL 故障切换 | 多节点：故障注入与切换时间线 | performance |
| [storage/mysql-backup-pitr](storage/mysql-backup-pitr/) | MySQL 误删恢复 | 多实例：隔离恢复与对照校验 | regular-docker |
| [java/atomics-and-longadder](java/atomics-and-longadder/) | volatile、CAS 与 LongAdder | 轻量：并发与吞吐 | performance |
| [java/class-loading-and-spi](java/class-loading-and-spi/) | 类加载与双亲委派 | 轻量：运行时编译与多个类加载器 | regular |
| [java/jit-and-escape-analysis](java/jit-and-escape-analysis/) | JIT 与逃逸分析 | 轻量：分配字节数 | regular |
| [java/jmm-visibility](java/jmm-visibility/) | JMM 与 happens-before | 轻量：默认、-Xint、volatile 三种运行方式 | regular |
| [java/thread-pool-order](java/thread-pool-order/) | 线程池参数怎么定 | 轻量：确定性输出 | regular |
| [java/virtual-threads](java/virtual-threads/) | 虚拟线程迁移 | 轻量：本机与 Docker JDK 21、25 对照 | performance |
| [spring/bean-lifecycle](spring/bean-lifecycle/) | Spring Bean 生命周期 | 轻量：真实 Spring 容器 | regular |
| [spring/boot-auto-configuration](spring/boot-auto-configuration/) | Spring Boot 自动配置 | 轻量：Spring Boot 4.1.1 | regular |
| [spring/circular-dependencies](spring/circular-dependencies/) | Spring 循环依赖 | 轻量：Spring Framework 与 Boot 对照 | regular |
| [spring/transaction-propagation](spring/transaction-propagation/) | Spring 事务传播 | 项目级：Spring Boot 4.1.1 + MySQL（Testcontainers） | regular-docker |
| [spring/async-executor-defaults](spring/async-executor-defaults/) | 线程池参数怎么定（Spring 默认执行器） | 轻量：Spring Boot 4.1.1 | regular |
| [spring/aop-boot-proxies](spring/aop-boot-proxies/) | Spring AOP 为什么会失效（Spring Boot 部分） | 轻量：Spring Boot 4.1.1 + H2 | regular |
| [spring/kafka-listener-group-protocol](spring/kafka-listener-group-protocol/) | Kafka 消费组重平衡（Spring Kafka 部分） | Spring Boot 4.1.1 + Kafka（Testcontainers） | regular-docker |
| [system-design/dependency-graph-execution](system-design/dependency-graph-execution/) | 依赖任务图的执行 | 轻量：单文件调度器 | regular |
| [system-design/sorting-selection-topk](system-design/sorting-selection-topk/) | 排序、选择与 Top K | 轻量：100 万条对象排序与选择 | performance |
| [system-design/amortized-cost-tail](system-design/amortized-cost-tail/) | 均摊代价与尾延迟 | 轻量：扩容尖峰与小 N 查找 | performance |
| [system-design/dedup-and-filter](system-design/dedup-and-filter/) | 判存结构与敏感词过滤 | 轻量：单文件，JOL 统计内存 | performance |
| [system-design/feature-design](system-design/feature-design/) | 典型业务功能的几个实测 | 轻量：单节点 Redis + JDK 客户端 | regular-docker |
| [system-design/short-url-service](system-design/short-url-service/) | 短链服务的四个契约问题 | 轻量：MySQL + JDK（含 curl） | regular-docker |
| [system-design/error-detection-codes](system-design/error-detection-codes/) | 校验和对上了，数据就没坏吗 | 轻量：穷举，确定性输出 | regular-docker |
| [system-design/async-acceptance](system-design/async-acceptance/) | 接口返回了 202，这件事算办完了吗 | 轻量：确定性输出 | regular-docker |
| [system-design/unread-count-watermark](system-design/unread-count-watermark/) | 未读数显示 3，点进去一条新消息也没有 | 标准：MySQL 8.4，确定性输出 | regular-docker |
| [system-design/hashmap-treeify](system-design/hashmap-treeify/) | HashMap 冲突链的树化条件与查找代价 | 轻量：确定性输出 | regular-docker |
| [distributed/http-timeout-layers](distributed/http-timeout-layers/) | 一次 HTTP 调用的超时分层 | 轻量：容器内服务端与客户端，JDK 21 与 25 | regular-docker |
| [distributed/retry-amplification](distributed/retry-amplification/) | 重试放大与超时预算 | 轻量：单 JVM 三级调用链 | regular |
| [distributed/lock-and-transaction](distributed/lock-and-transaction/) | 锁与事务的边界、状态流转、主键类型 | 轻量：MySQL 8.4 + JDBC | regular-docker |
| [distributed/redis-delay-queue](distributed/redis-delay-queue/) | Redis ZSET 延时队列 | 轻量：单节点 Redis + JDK 客户端 | regular-docker |
| [distributed/batch-vs-n-plus-one](distributed/batch-vs-n-plus-one/) | 分两次查：N+1 与批量查询 | 轻量：MySQL 8.4 + JDBC，模拟 1 ms 往返 | regular-docker |
| [distributed/capacity-knee](distributed/capacity-knee/) | 容量拐点 | 性能：MySQL 点查与更新的并发曲线、Redis 吞吐 | performance |
| [distributed/load-balancing-slow-instance](distributed/load-balancing-slow-instance/) | 十个实例里一个变慢，为什么十分之一的请求都超时了 | 轻量：离散事件模拟，确定性输出 | regular-docker |
| [distributed/snowflake-id-pitfalls](distributed/snowflake-id-pitfalls/) | 订单号重复了：雪花算法依赖的三件事 | 轻量：确定性输出 | regular-docker |
| [distributed/hedged-requests](distributed/hedged-requests/) | 给慢请求再发一份，系统反而被压垮了 | 轻量：离散事件模拟，确定性输出 | regular-docker |
| [distributed/circuit-breaker-defaults](distributed/circuit-breaker-defaults/) | 下游挂了一分钟，熔断器始终没有打开 | 轻量：确定性输出（Resilience4j 2.4.0） | regular-docker |
| [distributed/raft-states-with-etcd](distributed/raft-states-with-etcd/) | 写入、复制、提交、读取：etcd 三节点分区实测 | 常规：etcd 三节点容器 | regular-docker |
| [distributed/consistent-hashing](distributed/consistent-hashing/) | 一致性哈希的迁移、均衡与热点 | 轻量：确定性输出 | regular-docker |
| [distributed/quorum-beyond-overlap](distributed/quorum-beyond-overlap/) | W+R>N 之后还缺什么：quorum 的确定性模拟 | 轻量：确定性输出 | regular-docker |
| [distributed/xa-and-tcc](distributed/xa-and-tcc/) | 两阶段提交与 TCC：准备之后由谁保存决定 | 常规：MySQL 容器 | regular-docker |
| [network/dns-jvm-cache](network/dns-jvm-cache/) | DNS 切换与 Java 客户端 | 自建网络：CoreDNS + 两个后端 | regular-docker |
| [network/request-failure-signatures](network/request-failure-signatures/) | 请求失败在客户端的样子 | 轻量：自建网络 + netshoot + JDK 客户端 | regular-docker |
| [kubernetes/container-boundaries](kubernetes/container-boundaries/) | 容器的边界 | 轻量：Docker | regular-docker |
| [kubernetes/container-memory-accounting](kubernetes/container-memory-accounting/) | 进程只用了 10 MB，容器却因为内存被杀 | 轻量：cgroup v2 计数与 OOM | regular-docker |
| [kubernetes/deleted-open-files](kubernetes/deleted-open-files/) | 日志删掉了，磁盘空间没有回来 | 轻量：确定性输出 | regular-docker |
| [kubernetes/image-tag-identity](kubernetes/image-tag-identity/) | 同一个镜像 tag，两个节点上跑的不是同一个镜像 | 多节点：kind 与本地镜像仓库 | regular-docker |
| [kubernetes/image-layers-and-deleted-files](kubernetes/image-layers-and-deleted-files/) | Dockerfile 里删掉的文件，还在镜像里 | 轻量：Docker 构建与镜像包解析 | regular-docker |
| [engineering/ssh-and-git-identity](engineering/ssh-and-git-identity/) | GitHub SSH 与多账号 | 轻量：离线，OpenSSH 与 Git | regular |
| [engineering/postman-token-refresh](engineering/postman-token-refresh/) | Postman 集合的 token 脚本 | 轻量：Node 容器 + Newman | regular-docker |
| [engineering/expand-contract-rollback](engineering/expand-contract-rollback/) | 发布的可回退性 | 轻量：MySQL 8.4 + JDBC + Jackson | regular-docker |
| [engineering/untested-merge-result](engineering/untested-merge-result/) | 两个分支的检查都通过了，合并之后主干挂了 | 轻量：宿主机 git，确定性输出 | regular |
| [engineering/tcp-message-framing](engineering/tcp-message-framing/) | 发了三条消息，对方一次全收到了 | 轻量：回环连接，确定性输出 | regular-docker |
| [engineering/serialization-compatibility](engineering/serialization-compatibility/) | 上游加了一个字段，下游全部反序列化失败 | 轻量：确定性输出（Jackson 2/3、protobuf-java） | regular-docker |
| [engineering/async-logging-loss](engineering/async-logging-loss/) | 出事的那几秒，日志恰好是空的 | 轻量：结论确定（Logback 1.5.38） | regular-docker |
| [engineering/crypto-misuse](engineering/crypto-misuse/) | 用了 AES，密文还是被改了 | 轻量：确定性输出（JCE、Spring Security 7.1.1、jBCrypt） | regular-docker |
| [engineering/sql-injection-boundaries](engineering/sql-injection-boundaries/) | 全部用了预编译语句，还是被注入了 | 标准：MySQL 8.4，确定性输出 | regular-docker |
| [engineering/config-received-not-applied](engineering/config-received-not-applied/) | 配置中心显示已推送，线上行为没有变 | 轻量：确定性输出（Spring 7、Logback、HikariCP） | regular-docker |
| [engineering/accept-backlog](engineering/accept-backlog/) | 连接成功了，请求却一直没有响应 | 轻量：确定性输出（Linux 容器回环） | regular-docker |
| [engineering/cors-browser-behavior](engineering/cors-browser-behavior/) | 接口用命令行能通，浏览器里报跨域 | 标准：真实 Chromium，确定性输出 | regular-docker |
| [engineering/http-cache-reuse](engineering/http-cache-reuse/) | 页面已发布，用户为什么还在用旧版本 | 标准：真实 Chromium，确定性输出 | regular-docker |
| [engineering/regex-across-languages](engineering/regex-across-languages/) | 同一个正则在 Java、Python、JavaScript 里 | 轻量：确定性输出 | regular-docker |
| [engineering/regex-escape-layers](engineering/regex-escape-layers/) | 反斜杠的三层转义 | 轻量：确定性输出 | regular-docker |
| [engineering/unicode-length](engineering/unicode-length/) | 一个字符串有多长：字节、码点、UTF-16 与字形 | 轻量：确定性输出 | regular-docker |
| [engineering/vim-editing-language](engineering/vim-editing-language/) | Vim 的编辑语言 | 轻量：确定性输出 | regular-docker |
| [engineering/vim-file-encoding](engineering/vim-file-encoding/) | Vim 打开中文文件乱码 | 轻量：确定性输出 | regular-docker |
| [engineering/vim-runtime-loading](engineering/vim-runtime-loading/) | Vim 的配置与插件怎样加载 | 轻量：确定性输出 | regular-docker |
| [engineering/vim-options-quickfix-tags](engineering/vim-options-quickfix-tags/) | Vim 的局部选项、Tab 相关选项、quickfix 与 errorformat、:grep 与 :vimgrep、tags 的搜索模式与陈旧索引 | 已验证 | regular-docker |
| [engineering/vim-script-reload](engineering/vim-script-reload/) | Vim 脚本：重复 source 与 augroup、函数重定义、变量作用域、映射再解释、fnameescape、job 的退出状态 | 已验证 | regular-docker |
| [engineering/line-breaks](engineering/line-breaks/) | 硬换行、软折行与渲染：Vim 的 gq、J、wrap、modeline 与段落内换行在三种浏览器里的显示 | 已验证 | regular-docker |
| [engineering/compile-context-for-language-servers](engineering/compile-context-for-language-servers/) | 语言服务器的编译上下文：无编译数据库、CMake 生成的数据库、构建改了宏而数据库陈旧 | 已验证 | regular-docker |
| [engineering/git-partial-staging](engineering/git-partial-staging/) | 工作区与暂存区：add -p、拆分块、暂存后再修改、apply --cached、restore -p、reset --hard 与 fsck | 已验证 | regular-docker |
| [messaging/kafka-throughput](messaging/kafka-throughput/) | Kafka 为什么快 | 多节点：三节点 KRaft 集群 | performance |
| [messaging/kafka-delivery](messaging/kafka-delivery/) | Kafka 不丢、不重与 Exactly Once | 多节点：1 controller + 3 broker，停节点 | regular-docker |
| [messaging/kafka-rebalance](messaging/kafka-rebalance/) | Kafka 消费组重平衡 | 多节点：三节点 KRaft，约 12 分钟 | performance |
| [messaging/kafka-key-ordering](messaging/kafka-key-ordering/) | 按订单号发到同一个分区，为什么还是乱序了 | 单节点 Kafka 4.3.1，确定性输出 | regular-docker |
| [messaging/event-time-windows](messaging/event-time-windows/) | 晚到了五十秒的那次点击，算在哪一分钟 | 轻量：确定性输出（Kafka Streams 4.3.1） | regular-docker |
| [storage/pagination-and-index-cost](storage/pagination-and-index-cost/) | SQL 调优实战（第五、六节） | 轻量 | regular-docker |
| [storage/elasticsearch-behaviors](storage/elasticsearch-behaviors/) | Elasticsearch 的几个行为 | 轻量：单节点 ES 9.5.3 + JDK 客户端 | regular-docker |
| [storage/lsm-write-amplification](storage/lsm-write-amplification/) | 写了 180 MB，磁盘实际写了 700 MB | 轻量：RocksDB，只断言范围 | regular-docker |
| [storage/db-table-queue](storage/db-table-queue/) | 用数据库表当队列：提交顺序、SKIP LOCKED 与租约 | 常规：MySQL 8.4.11 容器，确定性输出 | regular-docker |
| [storage/mongodb-arbiter-majority](storage/mongodb-arbiter-majority/) | 仲裁节点、w:majority 与回滚 | 常规：MongoDB 9.0.4 三容器，PSA回滚与非投票从节点对照 | regular-docker |
| [storage/elasticsearch-bulk-failures](storage/elasticsearch-bulk-failures/) | bulk 的部分失败、重试与版本号 | 常规：Elasticsearch 9.5.5 单节点容器，确定性输出 | regular-docker |
| [java/aqs-and-locks](java/aqs-and-locks/) | 从 AQS 看 ReentrantLock | 轻量 | regular |
| [java/gc-baseline](java/gc-baseline/) | GC 基线与排查工具输出 | 轻量：JDK 21 容器 | regular-docker |
| [java/gc-collectors](java/gc-collectors/) | G1、分代 ZGC、Parallel 对比 | 性能：JDK 21 容器，4 CPU | performance |
| [java/call-and-mapping-cost](java/call-and-mapping-cost/) | 调用与映射的开销 | 性能：JMH、Maven | performance |
| [java/guava-eventbus](java/guava-eventbus/) | Guava EventBus 的四个行为 | 轻量：单文件 | regular |
| [java/maven-scope](java/maven-scope/) | Maven scope 与三种类路径 | 轻量：Maven 项目 | regular |
| [java/compact-object-headers](java/compact-object-headers/) | 压缩对象头的实际收益 | 轻量：JDK 25、26 容器 | regular-docker |
| [java/classfile-api](java/classfile-api/) | Class-File API 与类文件版本边界 | 轻量：JDK 25、26 容器 | regular-docker |
| [java/floating-point-summation](java/floating-point-summation/) | 同一组数，换个顺序求和，结果就不一样了 | 轻量：确定性输出 | regular-docker |
| [java/false-sharing](java/false-sharing/) | 两个线程各写各的变量，为什么互相拖慢 | 轻量：计时，只断言倍数范围 | regular-docker |
| [java/regex-backtracking](java/regex-backtracking/) | 正则只多了一个字符，匹配时间就翻倍 | 轻量：确定性输出，JDK 8 与 25 对照 | regular-docker |
| [java/java-time-pitfalls](java/java-time-pitfalls/) | 加一天，为什么只过了 23 小时 | 轻量：确定性输出 | regular-docker |
| [java/equality-and-collection-views](java/equality-and-collection-views/) | 同一个金额，HashSet 里是两个，TreeSet 里是一个 | 轻量：确定性输出 | regular-docker |
| [java/tls-certificate-chain](java/tls-certificate-chain/) | 浏览器能打开的 HTTPS 地址，Java 调用报 PKIX | 轻量：现场生成三级证书 | regular-docker |
| [java/maven-dependency-mediation](java/maven-dependency-mediation/) | 两行依赖对调了一下顺序，运行时少了一个方法 | 轻量：Maven 3.10.0，确定性输出 | regular-docker |
| [java/lock-identity](java/lock-identity/) | 加了 synchronized，计数还是少了 | 轻量：确定性输出 | regular-docker |
| [java/parallel-stream-common-pool](java/parallel-stream-common-pool/) | 一个 parallelStream，拖慢了不相干的接口 | 轻量：结论确定，耗时只断言区间 | regular-docker |
| [java/money-rounding-and-allocation](java/money-rounding-and-allocation/) | 对账差了一分钱 | 轻量：确定性输出 | regular-docker |
| [java/java-failure-paths](java/java-failure-paths/) | 出了错，日志里却没有可用的信息 | 轻量：确定性输出 | regular-docker |
| [java/jvm-warmup-first-requests](java/jvm-warmup-first-requests/) | 每次发版后的头几秒，接口都会超时 | 轻量：计时，只断言倍数关系 | regular-docker |
| [java/string-duplication](java/string-duplication/) | 两百万条记录，一百种城市名，占了一百多兆 | 轻量：堆占用，只断言范围 | regular-docker |
| [java/counter-overflow](java/counter-overflow/) | 计数器溢出：Math.abs(MIN_VALUE)、负数取余、轮询下标越界、floorMod 与掩码 | 已验证 | regular-docker |
| [cache/redis-data-structures-memory](cache/redis-data-structures-memory/) | Redis 数据结构与编码 | 轻量 | regular-docker |
| [cache/redis-memory-eviction](cache/redis-memory-eviction/) | Redis 内存满了会怎样 | 轻量 | regular-docker |
| [cache/redis-distributed-semaphore](cache/redis-distributed-semaphore/) | 基于 Redis 的分布式信号量 | 轻量 | regular-docker |
| [cache/redis-persistence-recovery](cache/redis-persistence-recovery/) | Redis 持久化与恢复 | 故障注入：进程崩溃与断电（LazyFS） | performance |
| [cache/redis-sentinel-failover](cache/redis-sentinel-failover/) | Redis Sentinel 故障切换 | 多节点：1 primary + 2 replicas + 3 Sentinel | performance |
| [cache/redis-latency-diagnostics](cache/redis-latency-diagnostics/) | Redis 为什么突然变慢 | 探测延迟与故障注入 | performance |
| [cache/redis-cluster-resharding](cache/redis-cluster-resharding/) | Redis Cluster 的应用契约 | 多节点：3 primary + 3 replicas | performance |
| [cache/redis-atomicity-and-locks](cache/redis-atomicity-and-locks/) | Redis 原子性边界 | 轻量 | regular-docker |
| [cache/cache-layer-latency](cache/cache-layer-latency/) | 缓存分层的访问延迟 | 轻量：Redis + MySQL + JDK 客户端 | performance |
| [cache/cache-aside-window](cache/cache-aside-window/) | 缓存一致性的窗口 | 轻量：Redis + MySQL + JDK 客户端 | regular-docker |
| [cache/zset-score-cursor](cache/zset-score-cursor/) | 有序集合按 score 分页：同分成员与复合游标 | 常规：Redis 8.10.1 容器，确定性输出 | regular-docker |
| [ai/ml-and-nlp-basics](ai/ml-and-nlp-basics/) | 机器学习与 NLP 基础 | 轻量：Python 容器，锁定依赖版本 | regular-docker |
| [ai/vector-search-pitfalls](ai/vector-search-pitfalls/) | 相似度 0.86，这两段文字其实毫不相干 | 轻量：numpy 合成向量，确定性输出 | regular-docker |
| [ai/retrieval-evaluation-pitfalls](ai/retrieval-evaluation-pitfalls/) | 新检索系统的评估分数只有旧系统的四分之一 | 轻量：确定性输出（合成数据） | regular-docker |
| [ai/clarification-agent-state](ai/clarification-agent-state/) | 等待人工澄清的代理：持久状态、重启恢复、错题与重复投递、过期、取消、提问预算 | 已验证 | regular-docker |
| [ai/structured-output-contract](ai/structured-output-contract/) | 模型输出的结构契约：语法、Schema 与领域三层，宽松与严格模式，修复改变含义，重试预算 | 已验证 | regular-docker |
| [ai/tool-runner-bounds](ai/tool-runner-bounds/) | 工具调用运行器：允许列表、参数校验、并行调用、步数上限、幂等重放、完成证据、工具结果中的指令、故障默认值 | 已验证 | regular-docker |
| [ai/rag-index-lifecycle](ai/rag-index-lifecycle/) | 知识库的增量更新：内容哈希键的旧块、删除传播、嵌入模型混用、权限过滤、答案缓存的键 | 已验证 | regular-docker |
| [ai/streaming-response-lifecycle](ai/streaming-response-lifecycle/) | 流式响应的生命周期：SSE 分帧、断开后异步与同步生成器、中途失败与显式错误事件 | 已验证 | regular-docker |
| [ai/attention-mask-semantics](ai/attention-mask-semantics/) | 注意力掩码的布尔约定与未来泄漏检验 | 轻量：确定性输出（PyTorch 2.14.1 CPU） | regular-docker |
| [ai/sft-label-alignment](ai/sft-label-alignment/) | 微调样本的标签对齐与 LoRA 初始化 | 轻量：确定性输出（PyTorch 2.14.1 CPU、Transformers 5.20.2） | regular-docker |
| [ai/pruning-and-quantization](ai/pruning-and-quantization/) | 剪枝、稀疏存储与量化改变了什么 | 轻量：确定性输出（PyTorch 2.14.1 CPU） | regular-docker |
| [ai/logits-and-loss-contracts](ai/logits-and-loss-contracts/) | logits、概率与损失函数的约定 | 轻量：确定性输出（PyTorch 2.14.1 CPU） | regular-docker |
| [ai/checkpoint-resume-state](ai/checkpoint-resume-state/) | 模型产物与训练续跑状态 | 轻量：确定性输出（PyTorch 2.14.1 CPU） | regular-docker |
| [ai/recall-ranking-contract](ai/recall-ranking-contract/) | 召回给排序设定的上限 | 轻量：确定性输出（标准库） | regular-docker |
| [testing/open-vs-closed-load-model](testing/open-vs-closed-load-model/) | 固定 10 个并发压测，服务卡了 2 秒，报告里只有 10 个慢请求 | 轻量：计时，只断言范围 | regular-docker |
| [testing/coverage-is-not-verification](testing/coverage-is-not-verification/) | 覆盖率 100%，一个断言都没有 | 轻量：JaCoCo 与 PIT | regular-docker |
| [testing/retry-hides-failures](testing/retry-hides-failures/) | 重跑一次就过了，这个缺陷去哪了 | 轻量：Surefire 重跑 | regular-docker |
| [testing/load-test-marker-propagation](testing/load-test-marker-propagation/) | 压测流量的标记，过了线程池就没了 | 轻量：确定性输出 | regular-docker |
| [testing/test-data-ownership](testing/test-data-ownership/) | 单独跑能过，一起跑就红 | 标准：MySQL 8.4，确定性输出 | regular-docker |
| [testing/behavior-scenarios-assertions](testing/behavior-scenarios-assertions/) | 从行为到场景到断言：失败信息、分组断言与重构安全 | 轻量：确定性输出 | regular-docker |
| [testing/test-doubles](testing/test-doubles/) | 替身证明了什么：匹配、契约与录制响应 | 轻量：确定性输出 | regular-docker |
| [testing/spring-test-transactions](testing/spring-test-transactions/) | Spring 测试事务的失真 | 轻量：确定性输出 | regular-docker |
| [testing/requests-api-testing](testing/requests-api-testing/) | 用 requests 写接口测试 | 轻量：确定性输出 | regular-docker |
| [testing/websocket-testing](testing/websocket-testing/) | WebSocket 接口测试：推送、乱序响应与关联 ID，超时、握手鉴权、关闭码与连接清理 | 已验证 | regular-docker |
| [testing/test-responsibility-migration](testing/test-responsibility-migration/) | 拆分测试的责任迁移：变异检验装配测试与注入规则测试、名不副实的测试、动态测试与 @BeforeEach | 已验证 | regular-docker |
| [testing/judge-workflow](testing/judge-workflow/) | 判题结果的绑定与有界执行 | 轻量：确定性输出（标准库，加固的容器） | regular-docker |

**黄金样板**按 [验证标准](docs/verification-standard.md) 的完整要求建设，用来确定同类实验的格式；**轻量实验**只保留核心代码、一条验证入口、原始输出和简要验证记录。

需要启动 Spring Boot、组合多个中间件的项目级验证（事务传播、Kafka 投递语义等），将在下一阶段用 Spring Boot 4 + Testcontainers 统一建设。

## 快速开始

```bash
make list                                   # 列出实验
make verify EXP=design/notification-routing # 运行单个实验并断言关键结论
make check                                  # 目录约定、元数据、脚本语法、敏感信息扫描
```

各实验的依赖写在自己的 README 中。常见要求：JDK 21+；Spring 实验需要 Maven 3.9+；带容器的实验需要 Docker（Compose v2）与 Python 3（PyYAML 仅用于 `make check`）。

## 输出边界、存储与数据执行实验

| 实验 | 验证范围 |
|---|---|
| [engineering/context-output-encoding](engineering/context-output-encoding) | 输出上下文、CSP 与 Cookie 的浏览器防御边界 |
| [engineering/uri-template-regex](engineering/uri-template-regex/) | URI 模板到正则：字面转义、捕获组错位、替换串转义、模板优先级与比较器契约 | 已验证 | regular-docker |
| [engineering/data-not-code-import](engineering/data-not-code-import/) | 让模型产出数据而不是代码：CSV 解析、BOM、Schema 校验、单事务导入与吞异常的假成功 | 已验证 | regular-docker |
| [distributed/traffic-response-paths](distributed/traffic-response-paths) | 重定向、TTL、全代理地址和 POST 重试 |
| [system-design/filesystem-raid-boundaries](system-design/filesystem-raid-boundaries) | 无特权离线 ext4 映射、删除和早先副本 |
| [system-design/trace-context-propagation](system-design/trace-context-propagation/) | 追踪上下文传播：线程池、CompletableFuture、虚拟线程、HTTP 请求头、采样标志、未结束的 span | 已验证 | regular-docker |
| [system-design/jwt-validation-checks](system-design/jwt-validation-checks/) | 访问令牌的校验项：默认解码器与签发者、受众、类型、exp 校验，算法混淆，scope 子串匹配 | 已验证 | regular-docker |
| [system-design/oauth-code-flow-binding](system-design/oauth-code-flow-binding/) | 授权码流程的请求绑定：PKCE 默认要求、码重放、redirect_uri、客户端、verifier、刷新令牌复用与轮换、撤销与本地验签 | 已验证 | regular-docker |
| [system-design/cross-shard-pagination](system-design/cross-shard-pagination/) | 跨分片分页：归并代价、单列游标与复合游标 | 轻量：确定性输出（纯计算模拟） | regular-docker |
| [system-design/bloom-not-a-constraint](system-design/bloom-not-a-constraint/) | 布隆过滤器的不存在与唯一约束 | 轻量：确定性输出（SQLite 与标准库） | regular-docker |
| [distributed/execution-plan-shuffle](distributed/execution-plan-shuffle) | Spark 执行计划、倾斜、加盐等价性与显式缓存 |
| [distributed/checkpoint-state-recovery](distributed/checkpoint-state-recovery) | Flink 单机 checkpoint 和受控算子失败恢复 |
| [distributed/sdk-retry-stacking](distributed/sdk-retry-stacking/) | SDK 自带重试与应用层重试叠加 | 轻量：确定性输出（openai 3.35.1，本地假服务） | regular-docker |

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
