# 验证记录：Maven scope 与三种类路径

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. `-DincludeScope=compile`：commons-logging（compile）与 MapStruct（provided），共 2 个——provided 在编译类路径上。
2. `-DincludeScope=runtime`：commons-logging（compile）与 Javassist（runtime），共 2 个——provided 不在运行时类路径上。
3. `-DincludeScope=test`：全部 4 个，ASM 只出现在这里。
4. 主代码引用了 provided 的 MapStruct，`mvn compile` 通过；只用运行时类路径运行，抛出 `NoClassDefFoundError: org/mapstruct/factory/Mappers`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。宿主机 JDK 21.0.5、Maven 3.9.9，maven-dependency-plugin 3.8.1。

## 三、执行步骤

见 `scripts/verify.sh`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证了直接依赖；传递依赖的 scope 规则引自 Maven 文档，本实验没有构造传递依赖。
- 可执行 jar 的打包方式（Spring Boot 插件、shade）各自决定打进哪些依赖，本实验用运行时类路径模拟「没有人提供 provided 依赖」的情况。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 否：三种类路径的结论与文章一致；新增的 NoClassDefFoundError 可补进 4.1 节 |
