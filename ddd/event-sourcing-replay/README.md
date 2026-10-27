# 事件溯源：决定、事实与重放

对应文章：[event-sourcing-decide-and-replay.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/event-sourcing-decide-and-replay.md)。

`src/Lab.java` 是一个约 300 行的最小事件溯源实现：工单的命令（开单、追加消息、关闭并评分）经 `decide` 产生事件，状态只由 `evolve` 从事件演算；事件存储按聚合版本做期望版本检查；读模型与回访策略是两个各自带检查点的订阅者。不依赖任何框架。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
