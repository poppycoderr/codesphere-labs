# 包之间的依赖：环、端口与 common 包

对应文章：[component-dependencies.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/component-dependencies.md)。

`code/` 下有三个版本的小代码库：v1 订单包与账单包互相引用；v2 订单包定义 `InvoiceIssuer` 端口、账单包实现它；v3 在 v2 上加一个 `common` 包，其中的工具方法引用了订单。`src/ComponentLab.java` 在 temurin 25 容器里依次编译每个版本，用 JDK 自带的 `jdeps -verbose:package` 列出包之间的依赖并计算不稳定度 I = Ce / (Ca + Ce)，再用 ArchUnit 1.5.1 的 `slices().matching("shop.(*)..").should().beFreeOfCycles()` 检查。依赖由 `pom.xml` 下载。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
