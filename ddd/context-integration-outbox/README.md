# 上下文集成：outbox、去重、乱序与契约

对应文章：[context-integration.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/context-integration.md)。

报名上下文确认报名后，计费上下文要生成一笔应收。两个上下文各有自己的库（`reg`、`billing`），报名上下文只通过集成事件 `registration.confirmed/v1`、`registration.cancelled/v1`（JSON）对外说话，计费上下文用防腐层把它翻译成自己的 `Receivable`。

单文件程序 `src/Integration.java` 在 JDK 21 容器中运行，五个场景：

1. 双写（提交后直接调用计费）与 outbox（业务行与待发送事件同一事务）在「提交后、调用前崩溃」时的差别；
2. 转发器在投递一批之后、标记已发送之前崩溃，重启后重复投递，消费端有无按 eventId 去重；
3. 同一笔报名的取消事件先于确认事件到达，消费端有无按聚合版本判断；
4. 一条金额字段写成字符串的毒消息：3 次失败后进入死信，不阻塞其他事件；
5. 契约：生产者新增字段、改名字段时，计费侧的翻译是否通过。

传输用进程内方法调用模拟，没有消息中间件；关注的是两端各自的事务、重试与去重语义。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```
