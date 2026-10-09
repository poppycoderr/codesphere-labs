# 验证记录：相等契约与集合视图

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 25.0.4.1。

1. `new BigDecimal("1.0")` 与 `"1.00"`：`equals` 为 false、`compareTo` 为 0；`HashSet` 里是 2 个，`TreeSet` 里是 1 个；`stripTrailingZeros` 之后 `HashSet` 里是 1 个。
2. `Integer` 127 `==` 127 为 true，128 `==` 128 为 false。`Map<Long, String>` 放入 `1L` 后 `get(1)` 返回 null。
3. 对象放进 `HashSet` 后修改参与 `hashCode` 的字段：`contains` 与 `remove` 都返回 false，`size` 仍为 1，遍历能找到它。
4. 只重写 `equals`：`HashSet.contains(相等的另一个对象)` 为 false，`ArrayList.contains` 为 true。
5. 父类 `Point` 与子类 `ColorPoint`：`p.equals(cp)` 为 true，`cp.equals(p)` 为 false；`List.of(cp).contains(p)` 为 true，`List.of(p).contains(cp)` 为 false。
6. 忽略大小写的比较器：`TreeSet` 加入 `Order-1` 与 `ORDER-1` 后只有 `[Order-1]`；`TreeMap` 依次 `put` 后是 `{Order-1=2}`。
7. 带 `int[]` 分量的 record，两个内容相同的实例 `equals` 为 false。
8. `Arrays.asList(int[])` 的 `size` 是 1；`Arrays.asList(Integer[])` 上 `set` 会改到原数组，`add` 抛 `UnsupportedOperationException`。
9. 对 `subList(1, 3)` 调用 `clear()` 后原列表从 `[1, 2, 3, 4, 5]` 变成 `[1, 4, 5]`；取了 `subList` 之后修改原列表，再访问 `subList` 抛 `ConcurrentModificationException`。
10. 源列表追加元素后，`unmodifiableList` 看到新元素，`List.copyOf` 看不到。`List.of(1, 2).contains(null)` 抛 `NullPointerException`。
11. `Stream.toList()` 的结果可以含 null，`add` 抛 `UnsupportedOperationException`；`Collectors.toList()` 的结果可以 `add`。`Collectors.toMap` 遇到重复键抛 `IllegalStateException`，遇到 null 值抛 `NullPointerException`。
12. 从 `keySet()` 删除键会删掉 Map 里的条目；`[10, 20, 30, 1]` 调用 `remove(1)` 删掉的是下标 1 的 20；`new ArrayList<>(源)` 之后修改副本里的元素，源里看到同样的修改。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- `Integer` 缓存的上界可以用 `-XX:AutoBoxCacheMax` 调大，128 `==` 128 的结果随之变化；这正是不能依赖它的原因。
- `Collectors.toList()` 返回可修改的 `ArrayList` 是当前实现，规范没有承诺返回类型与可修改性。
- 修改键之后 `contains` 返回 false 取决于新旧哈希值是否落在同一个桶，本例中不在；不能推出「一定找不到」，只能推出「不保证找到」。
- 没有覆盖并发修改、`IdentityHashMap`、`equals` 的传递性反例与 Lombok 等生成代码的默认行为。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-09 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
