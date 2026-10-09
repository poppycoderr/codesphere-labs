# 相等契约与集合视图

对应文章：[equality-and-collection-views.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/equality-and-collection-views.md)。

`src/EqualityLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，全部场景单线程顺序执行，输出是确定的，`scripts/verify.sh` 把它与预期逐行比较。

相等：`BigDecimal` 的 `equals` 与 `compareTo`、包装类型的 `==` 与跨类型 `equals`、修改已放入 `HashSet` 的键、只重写 `equals`、父子类的 `equals`、与 `equals` 不一致的比较器、带数组分量的 record。

视图与副本：`Arrays.asList`、`subList`、`Collections.unmodifiableList` 与 `List.copyOf`、`List.of` 对 null 的处理、`Stream.toList()` 与 `Collectors.toList()`、`Collectors.toMap` 的重复键与 null 值、`keySet()`、`remove` 的两个重载、浅拷贝。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```
