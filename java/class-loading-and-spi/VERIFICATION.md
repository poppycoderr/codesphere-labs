# 验证记录：类加载与 SPI

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **类型身份 = 类名 + 定义它的加载器**：两个加载器加载同一份 `com.example.Plugin`，名称相同、`Class` 对象不同；在 A 的代码里把 B 的实例转成 `Plugin`，抛出 `ClassCastException`，信息里分别列出 `loader 'plugin-b'` 与 `loader 'plugin-a'`。
2. **初始化时机**，按执行顺序：
   - 读取编译期常量 `Config.CONSTANT`：不触发初始化（值已编译进调用方）；
   - 创建 `Config[3]` 数组：不触发；
   - `Class.forName(name, false, loader)`：不触发；
   - 读取 `static final Integer BOXED`：触发。静态初始化里 `early = readLate()` 读到 `late` 的零值 0，之后 `late` 才被赋为 10。
3. **SPI 依赖线程上下文类加载器**：接口 `api.Greeter` 在父加载器、实现和 `META-INF/services` 在子加载器。接口里用 `ServiceLoader.load(Greeter.class)` 查找：上下文类加载器是父加载器时找到 0 个，是子加载器时找到 1 个；用接口自己的加载器查找找到 0 个。
4. **线程池会留住上下文类加载器**：在上下文为 `app` 时创建的单线程池，调用方切回原加载器后，工作线程的上下文类加载器仍然是 `app`。
5. **父优先与子优先**：父加载器里有 `lib.Version` 1.0、插件目录里有 2.0，父优先得到 1.0，子优先得到 2.0。子优先加载器尝试自己定义 `java.lang.Hacked`，被拒绝：`SecurityException: Prohibited package name: java.lang`。
6. **类加载器能否回收**（每次最多调用 20 次 `System.gc()`）：
   - 丢弃加载器和实例后：已回收；
   - 实例登记在父加载器的静态列表里：仍然存活；
   - 实例放进长期存活线程的 `ThreadLocal`：仍然存活；在该线程里 `remove()` 之后：已回收。
7. **编译期与运行期版本不一致**：调用方按 API 2.0 编译（调用 `call(int)`），运行时 classpath 上是 1.0，抛出 `NoSuchMethodError: 'java.lang.String api.Client.call(int)'`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。JDK 21.0.5。

## 三、执行步骤

`java src/ClassLoading.java build/tmp`：程序先清空 `build/tmp`，用 `javax.tools.JavaCompiler` 把各组源码编译到不同子目录，再按上面的顺序加载和观察。类加载器哈希在输出中替换为 `@<hash>`。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/output.tsv` | 各组观察的结果 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 回收检测依赖 `System.gc()` 与 `WeakReference`，JVM 不保证 `System.gc()` 一定执行回收；本机 20 次以内都得到了稳定结果。
- 没有覆盖 JPMS 模块层（`ModuleLayer`）与 `module-info` 中的 `provides/uses`；示例都在无名模块中。
- 子优先加载器是为实验写的最小实现，没有处理资源查找、并发注册等生产细节。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-25 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
