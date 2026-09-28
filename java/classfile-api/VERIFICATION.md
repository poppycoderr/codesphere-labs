# 验证记录：Class-File API 与类文件版本边界

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. JDK 25 上用 Class-File API 从零生成的类，主版本号为 69（JDK 25），加载后调用得到预期字符串。
2. 读入主版本号 65 的类（`--release 21`），替换一条 `ldc` 指令里的常量后，类文件仍是 65，重新加载、通过校验并调用得到 `"new"`；栈映射帧由 API 自动重新生成，示例代码里没有处理。
3. 版本边界（主版本号 70，JDK 26 编译）：
   - JDK 25 的 Class-File API：`IllegalArgumentException（Unsupported class file version: 70）`；
   - JDK 26 的 Class-File API：解析成功；
   - ASM 9.10.1：解析成功；ASM 9.7.1：`Unsupported class file major version 70`。

   Class-File API 跟着 JDK 走，只保证读当前及更早版本的类文件；ASM 靠发布新版本跟上新的类文件格式。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/jdk-versions.txt`](evidence/jdk-versions.txt)。

## 三、执行步骤

见 `scripts/verify.sh`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只演示了最小的生成与改写，没有覆盖 Java Agent 的 `ClassFileTransformer` 流程与重新定义已加载类。
- ASM 各版本支持的类文件版本以其发布说明为准；这里只验证了两个版本在主版本号 70 上的行为。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 是：字节码库一文 4.4 节补充实测示例与版本边界 |
