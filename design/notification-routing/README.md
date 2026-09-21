# 通知路由服务：从最小核心到遗留系统迁移

对应文章：[一个通知路由服务的演化](https://github.com/poppycoderr/codesphere/blob/master/docs/design/design-case-study.md)（另有 [API 设计与 DSL](https://github.com/poppycoderr/codesphere/blob/master/docs/design/expressive-java-api-and-dsl.md) 的内部 DSL 示例）。

这个实验要证明三件事：

1. 需求先写成 10 个测试，三次变化（新渠道、去重与静默、规则文本）都通过新增类型完成，测试全部通过；
2. 最终的 `Router` 不依赖渠道、抑制策略、规则文本和时钟，核心模型没有被外围变化污染；
3. 新旧两套逻辑处理同一批 10,000 条告警，差异会把旧系统中没写进文档的行为逐条暴露出来。

## 快速运行

```bash
make verify     # 约 5 秒；需要 JDK 21+，首次运行会下载 JUnit Console（约 2.7 MB）
```

不占用端口，不启动容器。

## 目录

| 路径 | 内容 |
|---|---|
| `src/Model.java` | `Alert`、`Route`、`Router`、`Channel`、`Suppression`、`Dispatcher` |
| `src/RuleText.java` | 值班负责人维护的规则文本解析与校验 |
| `src/Dsl.java` | 同一组路由的内部 DSL 写法 |
| `src/Legacy.java` | 旧服务 `LegacyAlertService` 与防腐层 `AlertTranslator` |
| `src/Shadow.java` | 影子比对：固定种子生成 10,000 条合成告警，三轮比较 |
| `tests/RouterTest.java` | 10 个测试：需求、三次变化、规则文本的等价性与报错 |
| `evidence/` | 原始输出 |
| `VERIFICATION.md` | 验证记录与结论边界 |
