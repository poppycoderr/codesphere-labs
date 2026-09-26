# 验证记录：Spring Boot 自动配置

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **四种条件**，用 `ApplicationContextRunner` 检查 `PaymentAutoConfiguration`：
   - 条件都满足：注册默认客户端，读到 `payment.endpoint`；
   - 用户自己声明了 `PaymentClient`：容器里只有 1 个，是用户的；
   - `payment.enabled=false`：没有 `PaymentClient`，条件报告为 `@ConditionalOnProperty (payment.enabled=true) found different value in property 'enabled'`；
   - classpath 上没有 `PaymentSdk`：没有 `PaymentClient`，条件报告为 `@ConditionalOnClass did not find required class 'labs.payment.sdk.PaymentSdk'`。
2. **自动配置之间的顺序影响 `@ConditionalOnMissingBean`**：
   - 兜底配置没有声明 `after`，排序后先于 `MetricsAutoConfiguration` 处理，判断时还没有 `MeterSink`：最终 2 个 `MeterSink`（`noop` 与 `prometheus`）；
   - 声明 `@AutoConfiguration(after = MetricsAutoConfiguration.class)`：最终只有 `prometheus`。
3. **自动配置不依赖组件扫描**：用真实的 `SpringApplication` 启动，主类在 `labs.app`，支付自动配置在 `labs.payment.autoconfigure`，经 imports 文件加载，`PaymentClient` 存在；既不在扫描范围、也不在 imports 文件里的 `InternalConfig` 没有生效。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)、[`evidence/dependencies.txt`](evidence/dependencies.txt)。Spring Boot 4.1.1、Spring Framework 7.0.9、JUnit 6.0.3（由 Boot 的依赖管理决定），JDK 21。

## 三、执行步骤

`mvn test` 运行 `AutoConfigurationTest` 的 3 个用例，事实写入 `target/facts.tsv`。条件报告通过 `ConditionEvaluationReport.get(beanFactory)` 读取，只保留 `PaymentAutoConfiguration` 上不匹配的条件。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/facts.tsv` | 每个用例记录的事实 |
| `evidence/test-results.txt` | 用例名与结果 |
| `evidence/maven-test.log` | Maven 输出（含 Spring Boot 启动日志） |
| `evidence/dependencies.txt` | 依赖版本 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 顺序实验里兜底配置的类名以 `Aa` 开头，是为了让它在字母序上排在前面、稳定复现问题；真实项目中两个自动配置谁先处理取决于排序规则，可能碰巧正确，所以更需要显式声明 `after`/`before`。
- 没有启动 Web 容器，也没有使用 Actuator 的 `conditions` 端点；条件报告的内容与 `--debug` 输出、Actuator 端点来自同一个 `ConditionEvaluationReport`。

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
| 2026-09-27 | 重新采集证据：日志中的本机用户名改为 `<user>`，断言与事实不变 | 否 |
