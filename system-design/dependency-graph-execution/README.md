# 依赖任务图的执行

对应文章：[dependency-graph-execution.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/dependency-graph-execution.md)。

单文件程序 `src/DagRunner.java`，以活动报名流程为例（资格校验 → 名额预留 → 费用计算、通知准备、风控复核 → 提交），五组对照：

1. 发布前校验：同一份配置里有环、环的下游、缺失节点、重复节点；
2. 稳定顺序：同一张图按两种顺序声明，比较普通队列与排序 ready 集合给出的执行顺序；
3. 并发上限 1、2、3 下的总耗时与同时运行的节点数；
4. 费用计算失败时，「跳过后继」与「立即取消」两种策略的最终状态；
5. 名额预留第一次调用已生效但响应超时，重试时带与不带幂等键占用的名额数。

## 快速运行

```bash
make verify     # 需要 JDK 21；约 5 秒
make evidence
make clean
```
