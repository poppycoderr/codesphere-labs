# 验证记录：API 写法的错误暴露时机与 record 的二进制兼容

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 同类型参数写反的构造器调用能编译、能运行
2. 普通 Builder 漏填必填项，在 `build()` 时抛 `NullPointerException`
3. 类型状态 Builder 漏填必填项，编译失败：`cannot find symbol ... location: interface NeedTeam`
4. 给 `record` 新增组件后，没有重新编译的构造器调用方抛 `NoSuchMethodError`，Builder 调用方正常运行

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21.0.5。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. `java src/styles/Styles.java`
2. `javac` 编译 `Missing.java`，期望失败
3. 用 v1 编译调用方，换上 v2 后不重新编译直接运行

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/styles.txt` | 构造器、Builder、类型状态 Builder、Lambda 配置器 |
| `evidence/type-state-missing-javac.txt` | 漏填时的编译错误 |
| `evidence/binary-compatibility.txt` | v1 编译的调用方在 v2 上运行 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 只验证了 `record` 规范构造器的签名变化；接口新增抽象方法导致 `AbstractMethodError` 等其他二进制不兼容情形以 JLS 第 13 章为依据，未单独实验。

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
