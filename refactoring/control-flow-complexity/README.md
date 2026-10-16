# 控制流与复杂度

对应文章：[control-flow-complexity.md](https://github.com/poppycoderr/codesphere/blob/master/docs/refactoring/control-flow-complexity.md)。

`src/Shipping.java` 是同一条运费规则的五种写法：层层嵌套、卫语句、switch 表达式、查表、每个地区一个策略对象。先在固定 digest 的 temurin 25 容器里用 PMD 7.28.0 计算每个方法的圈复杂度（CyclomaticComplexity）、认知复杂度（CognitiveComplexity）与 NPath（NPathComplexity），规则文件把阈值设为 1，让每个方法都报出数值；再运行程序，用 3 个地区 × 4 个金额 × 是否会员 × 是否超重共 48 组输入检查五种写法的结果是否一致。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 GitHub（下载 PMD 发行包）；约 30 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
