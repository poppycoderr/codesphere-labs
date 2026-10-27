# 组件的启动顺序、部分失败与逆序关闭

对应文章：[component-lifecycle-rollback.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/component-lifecycle-rollback.md)。

`src/Lab.java` 是一个最小的组合根：五个组件（config、db、cache、orders、http）按依赖图启动；注入启动失败与关闭失败，对比朴素实现与「只关闭已启动的、逆序、继续关闭并汇总」的实现。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
