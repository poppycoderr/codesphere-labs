# 验证记录：失败重跑

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 25.0.1（Maven 镜像自带）、JUnit Jupiter 6.1.3、Maven Surefire 3.6.0。

1. 不重跑：`RateTableTest.usdRate` 失败（`expected: <712> but was: <0>`），`Tests run: 1, Failures: 1`，构建失败，退出码 1。
2. `rerunFailingTestsCount=2`：第 1 次失败、第 2 次通过，汇总为 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Flakes: 1`，`BUILD SUCCESS`，退出码 0。第 1 次的失败信息保留在控制台的 `Flakes` 段落与 XML 报告的 `flakyFailure` 元素里。
3. 再加 `failOnFlakeCount=1`：测试统计相同，构建失败，原因是 `There is 1 flake and failOnFlakeCount is set to 1.`。
4. `InventoryTest` 一起运行并重跑 2 次：第二个测试 3 次都失败，实际值依次是 8、11、14（每次重跑又往共享的库存表里加了 3）。单独运行第二个测试：通过。
5. 模拟 10 万次构建（固定种子）：失败概率 0.3 的缺陷，不重跑时 29.958% 的构建失败，重跑 1、2、3 次时分别是 9.042%、2.600%、0.774%；失败概率 0.1 时分别是 9.942%、0.952%、0.082%、0.008%。与公式 p^(重跑次数+1) 一致。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh`、`pom.xml` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- `RateTable` 的缺陷被设计成「进程里第一次调用必然出错」，所以第 1 次失败、第 2 次通过是确定的；真实的偶发失败没有这么规律，这里只说明重跑机制如何处理「先失败后通过」。
- 模拟假设每次运行是否失败相互独立。真实的偶发失败常常相关（同一台慢机器、同一段拥塞），重跑的「通过率」会比公式低。
- 只验证了 Maven Surefire；Gradle、其他语言的测试框架和 CI 系统层面的重跑有各自的统计与报告方式。
- 没有覆盖界面自动化、网络依赖等更常见的偶发失败来源。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-05 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
