# 可扩展流程

对应文章：[strategy-template-and-pipeline.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/strategy-template-and-pipeline.md)。

单文件 `src/Pipeline.java`，以活动报名请求为例：

- 容量规则策略：「第一个匹配者胜出」与启动时建立索引（检查重复与缺失）；
- 固定流程加回调：回调在调用方线程与线程池中执行时的租户上下文与异常；
- 责任链：鉴权与限流的先后、幂等节点与校验节点的先后。

## 快速运行

```bash
make verify     # 需要 JDK 21
make evidence   # 重新采集 evidence/
make clean      # 清理本实验的构建产物
```
