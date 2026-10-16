# 验证记录：改名的边界

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| JSON 字段改名前后写出 | `{"sku":"A1","qty":3}` 与 `{"sku":"A1","quantity":3}` |
| 未升级的消费方读新消息（忽略未知字段） | `qty=0` |
| 已升级的一方读积压的旧消息 | `quantity=0` |
| 代码里改名、`@JsonProperty("qty")` 保留对外名字 | 写出仍是 `qty`，读旧消息得到 3 |
| 配置里写着方法名 `calc`，改名后反射调用 | `NoSuchMethodException` |
| 数据库里存的枚举名 `WAIT_PAY`，常量改名后读回 | `IllegalArgumentException` |
| 存的是序号 0，新版本在最前面插入 `CREATED` 后读回 | `CREATED` |
| 配置键改名、环境里的配置没改 | 读到 null |
| 直接调用改名后的方法 | 正常，编译器保证 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只演示了四种按名字查找的位置。框架的约定（Spring 的属性绑定、JPA 的列名推导、模板引擎里的属性访问、日志和监控里按名字聚合的指标）属于同一类，没有逐一实验。
- 配置键的例子用 `Map` 代替配置系统，说明的是「找不到就用默认值」这一行为模式。
- 没有覆盖二进制兼容（改名后已编译的调用方抛 `NoSuchMethodError`），见 design/api-evolution 实验。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-16 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
