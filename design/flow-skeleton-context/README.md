# 流程骨架里的上下文与失败语义

对应文章：[flow-skeleton-context.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/flow-skeleton-context.md)。

`src/Lab.java` 用一个「校验 → 幂等 → 执行 → 清理」的流程骨架逐项验证：ThreadLocal 上下文在嵌套、线程复用与异步下的所有权，先查后执行的幂等判断与随机事件 ID，只在正常返回后执行的收尾钩子，以及字符串键的上下文。并发交错用闩锁固定，输出确定。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
