# 失败重跑

对应文章：[retry-hides-failures.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/retry-hides-failures.md)。

在固定 digest 的 Maven 3.9.11 + temurin 25 容器里，用 Surefire 3.6.0 的 `rerunFailingTestsCount` 运行两组测试：

1. `RateTable` 有一个只在进程第一次查询时出现的缺陷（触发加载后没有等加载完成就读了旧值）。`RateTableTest` 分别在不重跑、重跑 2 次、重跑 2 次并设置 `failOnFlakeCount=1` 三种配置下运行；
2. `InventoryTest` 的两个测试共用一个进程内的库存表：一起运行（带重跑）与单独运行第二个测试；
3. `src/RetryOdds.java` 用固定种子模拟 10 万次构建：一个缺陷每次运行以 0.5、0.3、0.1、0.02 的概率让测试失败，重跑 0—3 次时构建失败的比例。

## 快速运行

```bash
make verify     # 需要 Docker；首次要下载 Maven 依赖，约 1 分钟
make evidence
make clean
```
