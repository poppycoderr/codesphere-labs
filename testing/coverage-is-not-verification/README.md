# 覆盖率不等于被验证

对应文章：[coverage-is-not-verification.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/coverage-is-not-verification.md)。

一个运费计算函数（`src/main/java/labs/ShippingFee.java`）和四个测试类，在固定 digest 的 Maven 3.9.11 + temurin 25 容器里运行：

1. `NoAssertTest`：每条路径都调用，不检查结果；
2. `TypicalValueTest`：每条路径取一个典型值并断言；
3. `BoundaryTest`：再补上每个分界点两侧的值；
4. `MissingRequirementTest`：针对一条需求里有、代码里没写的规则。

前三个各跑一遍 JaCoCo 0.8.15（行覆盖、分支覆盖）与 PIT 1.30.0（默认变异算子），汇总到 `summary.tsv`；第四个只跑测试，记录构建结果。测试与变异都是确定的，脚本把汇总与预期逐行比较。

## 快速运行

```bash
make verify     # 需要 Docker；首次要下载 Maven 依赖，约 2 分钟
make evidence
make clean
```
