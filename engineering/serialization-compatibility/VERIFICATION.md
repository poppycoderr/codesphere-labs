# 验证记录：序列化的版本兼容

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JSON：

| 场景 | Jackson 2.22.3 默认 | Jackson 3.2.3 默认 |
|---|---|---|
| 消息里多了一个接收方没有的字段 | `UnrecognizedPropertyException` | 忽略，正常读入 |
| 缺少 `int` 字段 | 0 | 0 |
| `int` 字段的值是 null | 0 | `MismatchedInputException` |
| 接收方没见过的枚举值 | `InvalidFormatException` | `InvalidFormatException` |
| 写 `Instant` | `InvalidDefinitionException`；注册 `JavaTimeModule` 后是 `1791619200.000000000` | `"2026-10-10T08:00:00Z"` |
| 合法 JSON 后面跟着多余内容 | 正常读入 | `StreamReadException` |

- 两种 `Instant` 写法，对方都能读。
- 「没传 nickname」与「传了 null」读进对象后都是 null；读成树时 `has` 分别是 false 与 true。
- 按旧版本的类读入、改一个字段、再写出：新字段丢失；用 `@JsonAnySetter` / `@JsonAnyGetter` 时保留。
- 读成 `Map` 后数字的类型：42 是 `Integer`，3000000000 与 72477573120020481 是 `Long`，19.99 是 `Double`；`(Long) map.get("small")` 抛 `ClassCastException`。

Protobuf：

| 场景 | 结果 |
|---|---|
| 旧版本解析新版本的消息 | 已知字段正常，编号 3 留在 unknown fields |
| 旧版本改了一个字段再发出去 | 新版本读到改后的值，`gift_wrap` 仍是 true |
| 编号 2 从 `email`（string）换成 `nickname`（string） | 旧数据的邮箱被读成昵称 |
| 编号 2 换成 `age`（int32） | `age` = 0，编号 2 进了 unknown fields，不报错 |
| 编号 3 从 int64 改成 int32 | 5000000000 读成 705032704 |
| 只改字段名 | 读到原值 |
| proto3 的 `int32 quantity`，填 0 与不填 | 序列化结果相同，都是 4 字节 |
| 改成 `optional int32 quantity` | 6 字节与 4 字节，`hasField` 为 true 与 false |
| proto3，旧版本读到新增的枚举值 | `UNKNOWN_ENUM_VALUE_Status_2`，数值 2 保留；转发后新版本读到 REFUNDING |
| proto2，同样的情况 | 读到默认值 CREATED，原值在 unknown fields；转发后新版本读到 REFUNDING |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- Jackson 的结果是「不加任何配置的 `ObjectMapper`」。Spring Boot 等框架会改默认值（例如关闭 `FAIL_ON_UNKNOWN_PROPERTIES`），以实际使用的 mapper 配置为准。
- Protobuf 用的是 `DynamicMessage`。生成代码对未知枚举值的 API 略有不同（proto3 的 getter 返回 `UNRECOGNIZED`），线上格式与 unknown fields 的行为一致。
- 没有覆盖 Protobuf 的 JSON 映射（字段名会出现在 JSON 里，改名不兼容）、Avro、Java 原生序列化。
- 没有覆盖 Editions 语法；`optional` 与枚举开放性在 Editions 里由 feature 控制。

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
