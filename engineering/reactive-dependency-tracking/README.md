# 响应式系统的三本账

对应文章：[reactive-dependency-tracking.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/reactive-dependency-tracking.md)。

`src/run.mjs` 包含一个约 30 行、可以分别打开「运行前清理依赖」「用栈恢复观察者」「排除自身」三个开关的最小响应式实现，与 @vue/reactivity 3.5.44 对照；另用 React 19.3.1 验证 useSyncExternalStore 对快照稳定性的要求。

```bash
make verify     # 需要 Docker；约 20 秒
make evidence   # 重新采集 evidence/
make clean
```
