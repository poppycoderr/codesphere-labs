# 验证记录：调用与映射的开销

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **调用方式**（JMH 平均耗时，误差为 99.9% 置信区间）：

| 调用方式 | 单次耗时 |
|---|---:|
| 直接调用 | 0.72 ± 0.07 ns |
| Byte Buddy 子类代理（透传） | 0.70 ± 0.01 ns |
| JDK 动态代理 | 2.42 ± 0.01 ns |
| Spring 重打包的 CGLIB 子类代理（透传） | 2.61 ± 0.02 ns |
| 反射 `Method.invoke` | 7.57 ± 0.04 ns |

   都在 10 ns 以内。Byte Buddy 的 `MethodDelegation` 生成的是普通方法调用，JIT 内联后和直接调用没有差别。
2. **映射方式**（8 个字段）：`BeanUtils.copyProperties` 289.04 ± 2.30 ns；MapStruct 2.44 ± 0.14 ns；手写 setter 7.54 ± 0.08 ns。反射映射比 MapStruct 慢两个数量级。手写反而比 MapStruct 慢：两者每次都分配 48 字节（`-prof gc`），生成的代码形状几乎一样，调试时把手写版本改成先读入局部变量也仍是 7.5 ns，原因没有深究。
3. **MapStruct 在编译期生成 `UserMapperImpl`**；目标对象多一个字段、`unmappedTargetPolicy=ERROR` 时，编译报 `Unmapped target property: "nickname".` 并失败。
4. **旧版字节码库在 JDK 21 上**：
   - CGLIB 3.2.5：`InaccessibleObjectException: Unable to make protected final java.lang.Class java.lang.ClassLoader.defineClass(...) accessible: module java.base does not "opens java.lang" to unnamed module`；
   - Byte Buddy 1.6.14：`UnsupportedOperationException: Cannot define class using reflection`，同样是无法通过反射调用 `ClassLoader.defineClass`；
   - Javassist 3.29.2-GA：`CtClass.toClass(neighbor)` 正常生成并调用类。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。宿主机 JDK 21.0.5、Maven 3.9.9，JMH 1.37。

## 三、执行步骤

见 `scripts/verify.sh`：`mvn package`，运行 `benchmarks.jar`，分别运行三个兼容性程序，用 `javac` 编译漏映射的例子。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 纳秒级的数字只在这台机器、这个 JDK 上成立，代理与反射的相对顺序可能随 JDK 版本变化；断言只检查：`BeanUtils` 比 MapStruct 慢 10 倍以上，反射和 JDK 代理慢于直接调用，所有调用方式都在 100 ns 以内。
- 代理只做透传；真实的拦截逻辑（日志、事务）会比代理本身贵得多。
- Byte Buddy 1.6.14 在更新的 JDK 上也可能先因 class 文件版本报错；在 JDK 21 上它先失败于类注入。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 是：调用与映射的数字改用 JMH 结果（原文为手写循环）；手写映射并不比 MapStruct 快；Byte Buddy 1.6.14 的报错改为类注入失败 |
