# 参数放在哪里

对应文章：[parameter-placement.md](https://github.com/poppycoderr/codesphere/blob/master/docs/refactoring/parameter-placement.md)。

`src/ParamLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。三个场景对应一个值的三种放置方式：作为参数传入、放进共享对象的字段、放进线程上下文。并发交错由闩锁固定，所以输出确定。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
