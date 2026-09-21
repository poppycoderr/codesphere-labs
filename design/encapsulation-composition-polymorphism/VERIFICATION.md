# 验证记录：继承与组合的计数差异、折扣规则组合与密封类型的穷举检查

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 继承 `HashSet` 重写 `add` 做计数，`addAll` 3 个元素后计数为 6；改用组合后为 3，换成 `TreeSet` 不用改计数逻辑
2. `HashSet.addAll` 声明在 `AbstractCollection`，内部逐个调用 `add`
3. 打 8 折再封顶减 30 的规则组合：100 元 → 80 元，500 元 → 470 元
4. 给密封接口新增 `BuyNGetOne` 后，没有更新的 `switch` 编译失败：the switch expression does not cover all possible input values

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21.0.5。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. `java src/Inherit.java`、`src/Rules.java`、`src/Sealed.java`
2. `javac src/v2-add-subtype/Sealed.java`，期望编译失败

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/inherit.txt` | 继承与组合的计数 |
| `evidence/rules.txt` | `addAll` 的声明位置与规则组合 |
| `evidence/sealed.txt` | 密封类型的正常结果 |
| `evidence/sealed-v2-javac.txt` | 新增子类型后的编译错误 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 计数翻倍依赖 `HashSet.addAll` 的实现细节（继承自 `AbstractCollection`），这正是文章要说明的风险；其他 JDK 版本若改变实现，结果可能不同。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 在会话临时目录中实测，数字见文章 | — |
| 2026-09-22 | 迁入本仓库，全部断言通过 | 见上文「误差、限制」中与文章不同的数字 |
