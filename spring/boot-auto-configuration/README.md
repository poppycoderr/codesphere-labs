# Spring Boot 自动配置

对应文章：[boot-auto-configuration.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/boot-auto-configuration.md)。

Maven 项目，Spring Boot 4.1.1（Spring Framework 7.0.9），版本由 `spring-boot-dependencies` 管理。

- `labs.payment.autoconfigure.PaymentAutoConfiguration`：`@ConditionalOnClass(PaymentSdk)`、`@ConditionalOnProperty(payment.enabled, matchIfMissing)`、`@ConditionalOnMissingBean`，注册在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；
- `labs.metrics`：一个提供真实 `MeterSink` 的自动配置，和两个兜底配置（一个声明了 `after`，一个没有）；
- `labs.app.ShopApplication`：`@SpringBootApplication`，扫描范围只有 `labs.app`；`labs.payment.internal.InternalConfig` 既不在扫描范围里，也不在 imports 文件里。

`AutoConfigurationTest` 用 `ApplicationContextRunner` 检查条件与顺序，再用真实的 `SpringApplication` 启动一次。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven
make evidence
make clean
```
