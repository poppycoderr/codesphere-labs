# 分层、应用服务与仓储

对应文章：[layers-services-and-repositories.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/layers-services-and-repositories.md)。

活动报名的一条用例「报名某个场次」，按 adapter / application / domain / infrastructure 四层组织，主代码不依赖任何框架：

```text
labs.ddd.layered
├── Bootstrap                 组合根，唯一同时认识四层的地方
├── adapter                   RegisterRequest → RegisterCommand，失败 → HTTP 状态码
├── application               RegisterForSession：事务入口、命令转换、提交后发布
├── domain                    Session 聚合根、Phone、领域事件、SessionRepository 端口
├── infrastructure            SessionRecord、SessionConverter、带事务的内存表、仓储实现
└── observability             Trace：记录经过的层，不属于任何一层
```

三组测试：

- `ArchitectureTest`：ArchUnit 1.5.0 的三条规则（与 DDK ArchGuard 中的分层、领域无框架、领域不依赖外层对应）检查主代码，并检查三个违规夹具；
- `UseCaseTraceTest`：成功路径的调用顺序，以及非法输入、领域拒绝、版本冲突三种失败分别停在哪里；
- `ConverterRoundTripTest`：200 个随机场次做 record → domain → record 往返，对照一个漏掉候补表的转换器。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven
make evidence
make clean
```
