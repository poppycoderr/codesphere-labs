# 验证记录：实体与值对象

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 实体按标识相等：同一 `RegistrationId` 修改手机号后仍相等；标识不同、其他属性完全相同时不相等。
2. 值对象按值相等：`Money(100)` 与 `Money(100.00)` 相等；`plus` 返回新值，原值不变；币种不同相加、人民币超过 2 位小数都在构造或运算时被拒绝。
3. 直接用 record 包 `BigDecimal`：`100.0` 与 `100.00` 的 `compareTo` 为 0，但 `equals` 为 false，放进 `HashSet` 后是 2 个元素；构造时统一 scale 后是 1 个。
4. 三种手机号写法解析后是同一个值 `+8613800138000`；非法手机号、容量 0、结束早于开始的时间段在构造时失败。
5. record 只是浅不可变：外部修改传入的列表、通过访问器追加，都会改变 `Tags` 的内容；构造时 `List.copyOf` 的 `CopiedTags` 不受影响，追加抛出 `UnsupportedOperationException`。数组组件按引用比较，内容相同的两个 record 不相等。
6. 保存时才分配标识的实体：保存前两个报名的 id 都为 null，放进 `HashSet` 后只剩 1 个；放进集合后再分配 id，`contains` 返回 false。创建时就生成类型化标识则没有这两个问题。
7. `register(SessionId, AttendeeId)` 传反参数时编译失败（`incompatible types`）；用 `String` 做标识时同样的错误可以编译通过。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21、junit 5.13.4。

## 三、执行步骤

`mvn test` 运行 `IdentityAndValueTest` 的 7 个用例，事实写入 `target/facts.tsv`。编译片段的用例把输出写到 `target/snippets`，错误信息按 `Locale.ENGLISH` 取第一行。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/facts.tsv` | 每个用例记录的事实 |
| `evidence/test-results.txt` | 用例名与结果 |
| `evidence/maven-test.log` | Maven 输出 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 没有使用 ORM。JPA 等框架的代理类、延迟加载对实体 `equals` 的影响不在本实验范围内。
- 手机号规则只覆盖中国大陆号段的格式，不代表号码真实存在。
- `Money` 的规范化依赖 `Currency.getDefaultFractionDigits()`；需要更高精度的场景（如单价、汇率）应使用不同的类型。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
