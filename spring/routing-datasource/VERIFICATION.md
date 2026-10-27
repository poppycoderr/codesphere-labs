# 验证记录：路由数据源在哪一刻选库

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 事务外设置路由键为 replica | 落到 replica |
| 事务方法内部设置 | 落到 primary |
| 进入事务之前设置 | 落到 replica |
| 包一层 LazyConnectionDataSourceProxy，在事务内第一条语句前设置 | 落到 replica |
| 按 isCurrentTransactionReadOnly 路由只读事务 | 直接用路由数据源落到 primary；延迟代理后落到 replica |
| 嵌套调用临时切换后 remove | 外层落回 primary；保存并恢复则仍是 replica |
| hash & (桶数 − 1) | 6 个桶只用到 0、1、4、5 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。

## 三、执行步骤

`mvn test` 运行 `src/test` 下的 JUnit 测试，测试把观察到的事实写入 `target/facts.tsv`；`scripts/verify.sh` 逐行断言。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 使用 DriverManagerDataSource 与 H2 内存库，只观察路由结果，不涉及连接池与真实复制延迟。
- Spring 事务管理器取连接的时机以 Spring Framework 7.0.10 的 DataSourceTransactionManager 为准；JPA 事务管理器同样在事务开始时取连接。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
