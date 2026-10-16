# 从行为到场景到断言：失败信息、分组断言与重构安全

对应文章：[behavior-scenarios-assertions.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/behavior-scenarios-assertions.md)。

一个普通的 Maven 项目：`src/main/java/lab/Shop.java` 是被测代码，故意包含几个错误（运费规则的边界把 `>=` 写成了 `>`、购物车少返回一件商品、订单摘要有两个字段算错），以及两版结果相同的计价（第二版只是把税率查询移到循环外）。`src/test/java` 下的测试有一部分按设计会失败，用来比较失败信息。`scripts/verify.sh` 在固定 digest 的 Maven 3.10.0 + temurin 25 容器里运行 `mvn test`（失败不中断），再用 `src/Report.java` 读取 surefire 的 XML 报告（开启了按显示名记录），输出每个测试的结果和失败信息的前四行。

## 快速运行

```bash
make verify     # 需要 Docker；约 2 分钟（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
