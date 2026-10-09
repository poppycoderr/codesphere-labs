# 验证记录：Maven 依赖仲裁

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

Maven 3.10.0，JDK 25.0.4.1。10 个应用全部 `BUILD SUCCESS`。

| 应用的依赖声明 | 类路径上的 util | 运行时调用 lib-b |
|---|---|---|
| `lib-a`、`mid`（util:1.0 在第 2 层，util:2.0 在第 3 层） | 1.0 | `NoSuchMethodError` |
| `lib-a`、`lib-b`（都在第 2 层，lib-a 在前） | 1.0 | `NoSuchMethodError` |
| `lib-b`、`lib-a`（顺序对调） | 2.0 | 正常 |
| `lib-a`、`lib-b`，依赖管理里写 util:2.0 | 2.0 | 正常 |
| `lib-a`、`mid`，再直接声明 util:2.0 | 2.0 | 正常 |
| `lib-a`、`lib-b`，依次导入 bom-x、bom-y | 1.0 | `NoSuchMethodError` |
| 同上，依次导入 bom-y、bom-x | 2.0 | 正常 |
| 自己的依赖管理条目 util:2.0 写在导入 bom-x 之前 | 2.0 | 正常 |
| 依赖管理里是 util:2.0，依赖声明里显式写 util:1.0 | 1.0 | `NoSuchMethodError` |
| 只有依赖管理条目，没有任何依赖声明 | 不在类路径上 | — |

带 `-Dverbose` 的依赖树把落选的版本标为 `(labs.mediation:util:jar:2.0:compile - omitted for conflict with 1.0)`。在第一个应用上打开 Enforcer：`dependencyConvergence` 与 `requireUpperBoundDeps` 都让构建失败，并指出 `util`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 结果对应 Maven 3.10.0 的解析器。Gradle 的默认策略不同（取最高版本），不适用这里的结论。
- 实验里的不兼容是「新版本多了一个方法」，旧版本胜出时表现为 `NoSuchMethodError`；反方向（新版本删了方法、新版本胜出）机制相同，没有单独构造。
- 没有覆盖版本范围、`exclusion`、可选依赖、分类器，以及两个不同坐标的 jar 里含有同名类的情况。
- 应用没有写测试；真实项目里覆盖到这条调用路径的测试能在构建阶段发现问题。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-10 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
