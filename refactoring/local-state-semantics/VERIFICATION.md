# 验证记录：局部状态与集合的契约

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 操作 | ArrayList | List.of | Arrays.asList | Stream.toList() | Collectors.toList() |
|---|---|---|---|---|---|
| add | 可以 | `UnsupportedOperationException` | `UnsupportedOperationException` | `UnsupportedOperationException` | 可以 |
| set | | `UnsupportedOperationException` | 可以 | | |
| 含 null 的元素 | 可以 | 构造时 NPE | | 可以 | |
| contains(null) | false | NPE | | | |

- `List.copyOf(含 null 的列表)` 抛 NPE。
- 源列表加入元素后：`Collections.unmodifiableList` 视图看到新元素，`List.copyOf` 快照不变。
- `final List` 变量照样能 `add`；record 构造后修改传入的列表，record 里看到修改。
- `final int fee;` 在 if/else 两个分支各赋值：编译通过；只在 if 分支赋值：`variable fee might not have been initialized`；先给占位值 0：编译通过，漏掉的分支返回 0。
- `var list = new ArrayList<>();` 推断为 `ArrayList<Object>`，放入 1 和 `"x"` 都能编译，取出赋给 `String` 时编译失败。
- `Optional.empty().get()` 抛 `NoSuchElementException`；`Optional.of(null)` 抛 NPE。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 结果来自 JDK 25.0.4.1 的实现。`Collectors.toList()` 返回可变列表是当前实现，规范没有承诺返回类型。
- 只覆盖了 `List`；`Set.of`、`Map.of` 有同样的 null 与不可变契约，没有逐一列出。

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
