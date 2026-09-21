# 验证记录：通知路由服务的演化与影子比对

## 一、待验证结论

| # | 文章中的结论 | 验证方式 |
|---|---|---|
| 1 | 需求写成 3 个测试，三次变化后共 10 个测试全部通过 | JUnit 测试 |
| 2 | 三次变化都通过新增类型完成，核心模型 `Router` 没有被外围变化修改 | 检查 `Router` 字节码中的类型引用 |
| 3 | 规则文本解析出的路由与代码定义的路由行为等价；写错的规则在加载时一次报出全部错误 | JUnit 测试 `文本规则与代码规则等价`、`规则写错在加载时报出全部错误` |
| 4 | 影子比对第一轮一致 9,269 条，差异来自 `critical` 写法 316 条、团队名大小写 95 条、夜间邮件 320 条 | `java Shadow` 输出 |
| 5 | 防腐层兼容旧写法后一致 9,617 条；夜间邮件登记为有意差异后 10,000 条全部一致 | 同上 |
| 6 | 内部 DSL 构造的路由与直接构造模型的行为一致 | `java Dsl` 输出 |

## 二、适用版本与环境

见 [`evidence/environment.txt`](evidence/environment.txt)：JDK 21.0.5、JUnit Platform 1.13.4、macOS arm64。只用到 JDK 标准库，不依赖容器。

## 三、场景与数据集

- 路由案例和旧服务都是为文章构造的虚构场景，不对应任何真实系统。
- 影子比对的输入由 `Shadow.replay(10_000, 42)` 生成：3 个团队按 3:2:1 分布（payment、search、growth），payment 中 2% 写成 `Payment`；级别中 3% 写成旧的 `critical`，其余 P1、P2、P3 按 1:1:4 分布；时间均匀分布在 2026-09-14 起的一周内。

## 四、执行步骤

`scripts/verify.sh` 依次执行：

1. `javac` 编译 `src/` 与 `tests/`；
2. JUnit Console 运行 `RouterTest`；
3. 运行 `Dsl` 与 `Shadow`；
4. `javap -c -p Router`，过滤外围类型名；
5. 对输出逐项断言，任何一项不满足即返回非零退出码。

## 五、原始证据索引

| 结论 | 证据 |
|---|---|
| 1、3 | [`evidence/test-output.txt`](evidence/test-output.txt) |
| 2 | [`evidence/router-dependencies.txt`](evidence/router-dependencies.txt) |
| 4、5 | [`evidence/shadow-output.txt`](evidence/shadow-output.txt) |
| 6 | [`evidence/dsl-output.txt`](evidence/dsl-output.txt) |

## 六、实际结果

- `test-output.txt`：10 个测试成功，0 个失败。
- `router-dependencies.txt`：除注释行外为空。
- `shadow-output.txt`：

```text
== 第一次影子比对（严格翻译）：一致 9269 / 10000
   316  新系统无法识别 severity=critical
   6  旧系统多发 [chat, email, phone]（team=Payment）
   22  旧系统多发 [chat, phone]（team=Payment）
   67  旧系统多发 [chat]（team=Payment）
   320  旧系统多发 [email]（team=payment）
== 第二次（翻译层兼容大小写与 critical）：一致 9617 / 10000
   7  旧系统多发 [email]（team=Payment）
   376  旧系统多发 [email]（team=payment）
== 第三次（夜间邮件确认下线，登记为有意差异）：一致 10000 / 10000
```

团队名大小写的 95 条是第一轮中 `team=Payment` 三类差异之和（6 + 22 + 67）。

## 七、结果解释

- 结论 2 只证明最终版本的 `Router` 没有依赖外围类型，不能证明历史上每一步都没有修改它；文章中「三次变化没有修改核心模型」是按设计过程叙述的，仓库只保留了最终代码。
- 影子比对的条数完全由固定种子和生成规则决定，任何机器上都应得到相同结果；它说明的是「差异会被分类暴露」这一方法，不说明真实系统中差异的比例。

## 八、误差、限制与不能推出的结论

- 测试耗时（文章中为 62ms）与机器和 JIT 状态有关，证据中已替换为 `<elapsed>`，不作为结论。
- 合成数据的分布是人为设定的，不能推出真实告警中旧写法或大小写问题的比例。
- 案例不包含真实的发送、重试与持久化，`Dedup` 的状态保存在内存中，不能推出多实例部署下的去重行为。

## 九、复现与清理命令

```bash
make verify     # 验证并断言，输出到 build/run
make evidence   # 重新生成 evidence/
make clean      # 删除 build/
```

## 十、验证历史

| 日期 | 环境 | 结果 | 文章是否需要更新 |
|---|---|---|---|
| 2026-09-21 | JDK 21.0.5，JUnit Platform 1.13.4 | 文章首次发布时在会话临时目录中运行，结果与本记录一致 | — |
| 2026-09-22 | 同上 | 迁入本仓库，全部断言通过 | 否，文末补充配套实验链接 |
