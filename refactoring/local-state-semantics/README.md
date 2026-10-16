# 局部状态与集合的契约

对应文章：[local-state-semantics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/refactoring/local-state-semantics.md)。

`src/LocalStateLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。检查几种常见「换写法」各自的契约：几种列表的增删改与 null、不可修改视图与快照、`final` 局部变量与 record 字段、确定赋值检查（通过 `javax.tools` 在程序里编译三段小源码）、`var` 与菱形一起使用时的推断、`Optional` 的几个方法。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
