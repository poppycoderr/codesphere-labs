# 验证记录：设计原则与设计模式两篇引用的 JDK 事实

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. `java.sql.Connection` 有 60 个公共方法，54 个抽象、6 个 default（接口隔离的反例）
2. `setShardingKey`、`setShardingKeyIfValid` 的默认实现抛 `SQLFeatureNotSupportedException`
3. `Collections.unmodifiableList` 是只读视图，底层列表变化会反映出来；`List.copyOf` 不会
4. 异常从抛出点到调用方的栈帧数：直接调用 1、三层装饰器 4、JDK 动态代理 5

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21.0.5。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. `java src/Facts.java`
2. `java src/Defaults.java`（用动态代理只实现抽象方法，调用 default 方法）
3. `java src/Frames.java`

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/facts.txt` | `Connection` 方法统计、只读视图与不可变集合 |
| `evidence/connection-defaults.txt` | default 方法的行为 |
| `evidence/stack-frames.txt` | 装饰器与动态代理的栈帧数 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 栈帧数只统计从抛出点到调用方法之间的帧，不含 JDK 启动器的帧；动态代理的帧数与 JDK 版本的代理实现有关。

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
