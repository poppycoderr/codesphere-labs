# 验证记录：record 浅不可变、组合顺序、Stream 副作用与 Optional

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. `record` 是浅不可变：调用方修改传入的列表，`record` 的内容跟着变；紧凑构造器里 `List.copyOf` 后不受影响
2. 同样两条规则，`andThen` 顺序不同：200 元得到 130 元和 136 元
3. `List` 的 `stream().peek(...).count()` 不执行 `peek`；加 `filter` 后执行 5 次
4. 并行流往 `ArrayList` 里 `add` 会丢元素或抛异常；改用 `toList()` 20 轮全部正确
5. 值存在时 `orElse` 仍会调用默认值方法，`orElseGet` 不会
6. 递归 `computeIfAbsent`：`ConcurrentHashMap` 抛 `IllegalStateException: Recursive update`，`HashMap` 抛 `ConcurrentModificationException`

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21.0.5。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. `java src/Fn.java`
2. 逐行断言；并行流一项只断言出错轮数大于 0

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/fn-output.txt` | 全部 6 组实验的输出 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 并行流出错的轮数取决于调度和核数：文章首次实测 20 轮全部出错（14 轮丢元素、6 轮抛异常），本仓库归档的一次是 19 轮（16 + 3）。只能推出「会出错」，不能推出出错概率。

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
