# 改名的边界

对应文章：[naming-and-renaming.md](https://github.com/poppycoderr/codesphere/blob/master/docs/refactoring/naming-and-renaming.md)。

`src/RenameLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，依赖 Jackson 3.2.3。同一个改名（字段 `qty` 改成 `quantity`、方法 `calc` 改成 `calculateFee`、枚举常量 `WAIT_PAY` 改成 `AWAITING_PAYMENT`、配置键 `order.max-qty` 改成 `order.max-quantity`）放到四种「按名字查找」的位置，观察旧数据与旧调用方的结果；最后用 `@JsonProperty` 保留对外名字。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 20 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
