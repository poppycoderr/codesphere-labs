# 观察者的契约

对应文章：[observer-contracts.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/observer-contracts.md)。

`src/run.mjs` 对比三种实现：朴素发布器（普通对象做事件表、forEach 通知、splice 退订、先调用后摘除的 once）、改进版发布器（Map、快照通知、先摘除后调用、逐个隔离异常并汇总），以及 Node 的 EventEmitter 与 EventTarget。不依赖第三方包。

```bash
make verify     # 需要 Docker；约 5 秒
make evidence   # 重新采集 evidence/
make clean
```
