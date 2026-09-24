# 验证记录：上下文集成

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **双写会丢**：报名提交后直接调用计费，每 10 次在调用前模拟 1 次进程崩溃：报名 100 条，应收 90 条。
2. **outbox 把「业务写入」和「要发一个事件」放进同一事务**：100 笔报名各写一条 outbox；另有 1 笔在提交前失败，报名与 outbox 都没有留下。转发器处理后应收 100 条。
3. **outbox 之后仍然是至少一次**：50 个事件，转发器在第 3 批（10 条）投递之后、标记之前崩溃，重启后这 10 条再投递一次，计费一共处理 60 次：
   - 不去重：应收 60 条；
   - 按 eventId 写 inbox 去重（与应收同一事务）：应收 50 条，跳过 10 次。
4. **乱序**：20 笔报名先确认后取消，投递顺序与产生顺序相反：
   - 只去重、不判断版本：取消到达时找不到应收，被丢弃；随后到达的确认生成应收，最终 20 条都错误地保持 OPEN；
   - 按聚合版本判断：取消先记下 VOID（版本 2），随后到达的确认（版本 1）被当作过期事件忽略，最终 20 条都是 VOID。
5. **毒消息**：31 个事件中 1 个 `feeCents` 写成了字符串 `"19.9 元"`。转发器运行 3 次后，它进入死信（`字段 feeCents 不是整数`），outbox 标记为 FAILED；其余 30 条正常生成应收，没有事件仍待发送。
6. **契约**：生产者新增字段 `channel`，计费侧的翻译照常通过（只读取约定的字段）；把 `feeCents` 改名为 `amountCents`，翻译失败并指出「缺少字段 feeCents」。这类检查放在生产者的发布流水线里，改名就会在上线前被拦下。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/container.txt`](evidence/container.txt)。MySQL 8.4.11（2 CPU、1 GB，固定 digest），Connector/J 8.0.27，Gson 2.11.0。

## 三、执行步骤

`scripts/verify.sh` 启动共享的 MySQL 容器、建两个库（`schema/01-schema.sql`），在 JDK 21 容器中运行 `src/Integration.java`，每个场景前清空表；结束后导出最后一个场景的 outbox 状态、死信和一条 payload（UUID 替换为 `<uuid>`）。

崩溃用抛出异常模拟：双写在调用计费前 `continue`，转发器在标记前抛出 `RelayCrashed`，随后新建一次转发调用代表进程重启。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/output.tsv` | 五个场景的结果 |
| `evidence/final-tables.txt` | 毒消息场景结束时的 outbox 状态、死信与一条 payload |
| `evidence/container.txt` | 镜像与依赖版本 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 没有消息中间件。Kafka 等中间件自身的投递语义（生产者重试、分区内顺序、消费位点提交）见站内的 Kafka 文章；这里的结论只依赖「投递至少一次、顺序不保证」这两个前提。
- 转发器是单线程轮询。多个转发器实例同时运行时，需要 `SELECT ... FOR UPDATE SKIP LOCKED` 或租约来分配 outbox 行，本实验没有覆盖。
- 版本判断依赖事件携带聚合版本号，且同一聚合的版本单调递增；跨聚合的顺序没有保证。
- 两个上下文的库在同一个 MySQL 实例中，只是为了证明「各写各的库」，不代表部署建议。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
