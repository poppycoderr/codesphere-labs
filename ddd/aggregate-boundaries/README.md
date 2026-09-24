# 聚合边界

对应文章：[aggregate-boundaries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/aggregate-boundaries.md)。

活动报名案例，MySQL 8.4.11 容器 + JDK 21 容器运行单文件程序 `src/Aggregates.java`，四组对照：

1. **候补递补放在哪里**：场次容量 20 已满、候补 20 人。10 位已确认者取消，同时 10 位新用户报名。
   - `combined`：候补队列在场次聚合内，取消与递补在同一事务；
   - `split`：候补队列是独立聚合，取消提交后发出「名额已释放」事件，5ms 后由另一个事务递补。
2. **聚合的大小**：200 个请求分散在同一活动的 8 个场次上，每个事务持锁 2ms。`event-root` 先锁活动行，`session-root` 只锁场次行。
3. **事件在回滚时是否流出**：领域方法里直接发布，与聚合登记、提交后再发布。
4. **只能经由根修改**：只读视图与副本的区别。

与 [design/aggregate-concurrency](../../design/aggregate-concurrency/) 的分工：那个实验比较同一个聚合上四种并发写法，这里比较聚合边界本身画在哪里。

## 快速运行

```bash
make verify     # 需要 Docker、Python 3；约 1 分钟
make evidence
make clean      # 删除容器与数据卷
```
