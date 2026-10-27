# 验证记录：Spring Data 领域事件与事务阶段

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| save 一个登记了事件的新聚合，再 save 一次 | 发布 1 次（事务内），第二次 save 不再发布 |
| close() 后依赖脏检查、不调用 save | 数据库状态已是 CLOSED，事件发布 0 次 |
| 只有 @DomainEvents，save 三次 | 发布 3 次 |
| PO 构造器里登记「已申请」，申请后再关闭 | TicketApplied 2 次、TicketClosed 0 次 |
| 事件由领域行为产生、映射时带过去 | TicketApplied 1 次、TicketClosed 1 次 |
| AFTER_COMMIT 里不开新事务写入（连接池默认 autoCommit=true） | 写入被提交 |
| 同上，连接池 autoCommit=false | 写入丢失 |
| AFTER_COMMIT 监听器标注 REQUIRES_NEW | 两种配置下都提交 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。

## 三、执行步骤

`mvn test` 运行 `src/test` 下的 JUnit 测试，测试把观察到的事实写入 `target/facts.tsv`；`scripts/verify.sh` 逐行断言。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 使用 H2 内存库；不开新事务的写入是否被提交取决于事务管理器清理连接时是否切换 autoCommit 以及驱动对 setAutoCommit(true) 的处理，属于实现细节而不是契约，换数据库、连接池或事务管理器都可能改变结果。
- Spring Data 在哪些仓储方法上发布事件以 Spring Data Commons 4.1.2 的实现为准。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
