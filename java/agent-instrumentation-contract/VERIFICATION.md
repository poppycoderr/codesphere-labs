# 验证记录：插桩不能改变业务结果

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 进入通知抛异常 | 无 suppress：业务方法抛 IllegalStateException；有 suppress：返回 300 |
| 方法抛异常 | 默认出口通知不执行（进入 2、出口 1）；onThrowable 时成对，原异常照常抛出 |
| @Return Object 读基本类型 | long 装箱为 Long；void 为 null |
| 构造器进入通知读 @This | 转换失败只报给监听器，类以原样加载，通知 0 次 |
| removeTransformer | 已转换的类仍计数；retransformClasses 后恢复 |
| ByteBuddy reset | 恢复原样；对已移除的转换器 reset 返回 false |
| retransform 加字段 | UnsupportedOperationException：attempted to change the schema |
| 两个转换器 | 调用 1 次计数 2 |
| 截断 + 遮数字 | 卡号全遮；密码前 16 个字符原样采到 |
| 上报队列满 | put：业务调用完成 5 次后卡住；offer：20 次完成，丢弃 15 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 所有业务类与 agent 在同一个类加载器里；应用服务器、OSGi 等多类加载器环境下，通知引用的类是否可见是另一个问题。
- JDK 25 对 ByteBuddy 使用 sun.misc.Unsafe 打印弃用警告（stderr，已忽略）；动态附加 agent 的警告与 -XX:+EnableDynamicAgentLoading 不在本实验范围。
- 队列阻塞用 500ms 等待判断「卡住」；实际系统里消费者变慢时表现为业务延迟上升，而不一定是完全卡住。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-28 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
