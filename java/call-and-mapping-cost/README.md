# 调用与映射的开销

对应文章：[bytecode-libraries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/bytecode-libraries.md)、[bean-mapping.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/bean-mapping.md)。

Maven 项目，`mvn package` 生成 JMH 的 `target/benchmarks.jar`：

1. `CallBench`：同一个 `greet(String)`，直接调用、JDK 动态代理、`Method.invoke`、Byte Buddy 1.18.14 子类代理（`MethodDelegation` + `@SuperCall`）、Spring 7.0.9 重打包的 CGLIB 子类代理，代理都只做透传；
2. `MappingBench`：8 个字段的对象，手写 setter、MapStruct 1.6.3、Spring `BeanUtils.copyProperties`；
3. `compat/`：用原版 CGLIB 3.2.5（ASM 5.2）、Byte Buddy 1.6.14、Javassist 3.29.2-GA 在当前 JDK 上生成类；
4. `compat/bad-mapper/`：目标对象比源对象多一个字段，用 `-Amapstruct.unmappedTargetPolicy=ERROR` 编译。

JMH 参数：每个基准 1 个 fork，预热 3 × 1 秒，测量 5 × 1 秒，平均耗时模式。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven、Python 3；约 2 分钟（首次需要下载依赖）
make evidence   # 重新采集 evidence/
make clean
```
