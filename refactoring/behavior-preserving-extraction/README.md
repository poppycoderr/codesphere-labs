# 保行为的方法提取

对应文章：[behavior-preserving-extraction.md](https://github.com/poppycoderr/codesphere/blob/master/docs/refactoring/behavior-preserving-extraction.md)。

`src/ExtractionLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。一个把校验、计价、预占库存与写审计混在一起的方法，被拆成四个版本：一个按原顺序拆分，另外三个各犯一个拆分时常见的错误。固定种子生成 2000 个订单（含空订单、非法数量、缺货商品、优惠码与会员的组合），每个订单分别交给原方法与新版本，比较返回值或异常，以及副作用（预占、审计）的顺序。这是字符化测试的做法：不预先写出期望值，以原方法的行为为准。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
