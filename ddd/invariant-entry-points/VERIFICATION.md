# 验证记录：不变量的入口

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 入口 | 结果 |
|---|---|
| `new Quantity(0)` | 被拒绝 |
| JSON `{"value":0}` → 带无参构造器、只有 getter 的类 | 得到 `Quantity(0)` |
| 同样的 JSON → record（紧凑构造器校验） | 被拒绝 |
| 同样的 JSON → 只有 `@JsonCreator` 构造器的类 | 被拒绝 |
| Java 序列化字节里的 5 改成 0 后反序列化 | 得到 `Quantity(0)` |
| 加了 `readObject` 校验的类，同样改过的字节 | 被拒绝 |
| 反射直接写私有字段 | 得到 `Quantity(0)` |
| 购物车已有 3 行，调用 `add` | 被拒绝 |
| 从 `lines()` 拿到内部列表直接 `add` | 购物车有 4 行 |
| 改成返回 `unmodifiableList` 后 `add` | 被拒绝 |
| 但对列表里的元素 `setQty(0)` | 购物车里是 `[x0]` |
| 新订单直接 `ship()` | 被拒绝 |
| 调用父类的公共 `setStatus("SHIPPED")` | 状态变成 SHIPPED，没有经过支付 |
| `Long` 类型 id 用 `==` 比较 | 127 为 true，128 为 false；`equals` 都为 true |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- Jackson 的结果来自不加配置的 `JsonMapper`。它在类只有 getter、没有 setter 时，会推断出通过私有字段写入（`INFER_PROPERTY_MUTATORS` 默认开启）；关掉这个特性或调整可见性，结果会不同。
- 反射写字段代表的是 ORM 与对象映射工具的常见做法，实验没有使用具体的 ORM。
- Java 序列化只演示了改动一个字节，实际的反序列化攻击面更广，不在本实验范围内。

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
