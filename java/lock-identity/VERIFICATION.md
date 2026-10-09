# 验证记录：锁对象的身份

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 不加锁 | 丢更新 |
| 两个线程锁同一个对象 | 5 轮都是 400000 |
| 两个实例各自的 `synchronized` 实例方法修改同一个静态变量 | 丢更新；一个线程在实例 1 的同步方法里时另一个线程照样进入实例 2 |
| 改成 `static synchronized` | 5 轮都是 400000 |
| 一个方法 `synchronized`、另一个方法 `ReentrantLock`，保护同一个字段 | 丢更新 |
| 同步块锁的是方法里刚 `new` 的对象 | 丢更新 |
| `synchronized (boxed) { boxed++; }` | 丢更新；自增后变量指向另一个对象；`javac` 给出 `attempt to synchronize on an instance of a value-based class` 警告 |

拿业务键当锁：

| 锁对象 | 另一个线程 |
|---|---|
| 两个运行时拼出来的 `"order-42"`（`equals` 为 true，`==` 为 false） | 照样进入 |
| 各自 `intern()` 之后 | 被挡住 |
| 两个互不相干的类各自的字面量 `"LOCK"` | 被挡住 |
| `Integer.valueOf(100)` 取两次 | 被挡住 |
| `Integer.valueOf(1000)` 取两次 | 照样进入 |
| `Long.valueOf(20261010L)` 取两次 | 照样进入 |
| 按键从 `ConcurrentHashMap` 里 `computeIfAbsent` 取锁对象，同一个键 | 被挡住 |
| 同一张表里的另一个键 | 照样进入 |

- 写方在锁内先改 a 再改 b（约定 a + b 恒为 0），读方不加锁、在两次修改之间读：读到 1；读方加同一把锁：读到 0。
- `Collections.synchronizedList` 上两个线程各自「为空才添加」，都先检查完再添加：列表里有 2 个元素。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 丢更新的场景依赖两个线程真的同时运行，单核环境下可能 5 轮都不丢；实验机有多个核。
- `Integer.valueOf` 的缓存范围默认是 -128 到 127，可以用 `-XX:AutoBoxCacheMax` 调大；`Long.valueOf` 的缓存范围固定为 -128 到 127。实验用的是默认参数。
- 只覆盖单个 JVM 内的锁。服务部署多个实例时，JVM 内的任何锁都管不到另一个实例，这不在本实验范围内。
- 按键取锁对象的表在实验里只增不减；实际使用需要回收不再使用的条目，回收与加锁之间的竞争没有验证。

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
