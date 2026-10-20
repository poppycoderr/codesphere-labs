# 拆分测试时的覆盖责任

对应文章：[test-responsibility-migration.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/test-responsibility-migration.md)。

`src/` 里是一个最小容器（按类型 + 限定符登记）、一条注入规则（Report 向 Context 要两个限定的 Source）和一个异常响应器（先查映射器、再调用映射器，失败回落 500）。测试分为四组：真实容器的装配测试、用桩 Context 的注入规则测试、异常响应器的三个测试（其中一个名不副实）、一个 JUnit 测试工厂与一个普通测试。`scripts/verify.sh` 用系统属性 `lab.mutant` 让同一组测试在三种代码下各跑一次：正确代码、登记时丢掉限定符、映射器抛异常时不再回落；用固定 digest 的 Maven 3.10.0 容器运行，再由 `src/Report.java` 汇总 surefire 报告。

## 快速运行

```bash
make verify     # 需要 Docker；约 2 分钟（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
