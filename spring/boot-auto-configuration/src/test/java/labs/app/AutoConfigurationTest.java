package labs.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import labs.metrics.AaFallbackSinkAutoConfiguration;
import labs.metrics.MeterSink;
import labs.metrics.MetricsAutoConfiguration;
import labs.metrics.OrderedFallbackSinkAutoConfiguration;
import labs.payment.autoconfigure.PaymentAutoConfiguration;
import labs.payment.autoconfigure.PaymentClient;
import labs.payment.sdk.PaymentSdk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AutoConfigurationTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PaymentAutoConfiguration.class))
            .withPropertyValues("payment.endpoint=https://pay.example.test");

    @Configuration
    static class UserConfig {
        @Bean
        PaymentClient myClient() {
            return () -> "用户自己的客户端";
        }
    }

    @Test
    void conditions() {
        runner.run(ctx -> Facts.record("cond.default", "默认：" + ctx.getBean(PaymentClient.class).describe()));
        runner.withUserConfiguration(UserConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(PaymentClient.class);
            Facts.record("cond.user_bean", "用户声明了 PaymentClient：容器里 " + ctx.getBeanNamesForType(PaymentClient.class).length
                    + " 个，是「" + ctx.getBean(PaymentClient.class).describe() + "」");
        });
        runner.withPropertyValues("payment.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(PaymentClient.class);
            Facts.record("cond.disabled", "payment.enabled=false：PaymentClient " + ctx.getBeanNamesForType(PaymentClient.class).length + " 个；" + negative(ctx));
        });
        runner.withClassLoader(new FilteredClassLoader(PaymentSdk.class)).run(ctx -> {
            assertThat(ctx).doesNotHaveBean(PaymentClient.class);
            Facts.record("cond.no_sdk", "classpath 上没有 PaymentSdk：PaymentClient " + ctx.getBeanNamesForType(PaymentClient.class).length + " 个；" + negative(ctx));
        });
    }

    static String negative(ConfigurableApplicationContext ctx) {
        return ConditionEvaluationReport.get(ctx.getBeanFactory()).getConditionAndOutcomesBySource().entrySet().stream()
                .filter(e -> e.getKey().equals(PaymentAutoConfiguration.class.getName()))
                .flatMap(e -> e.getValue().stream())
                .filter(o -> !o.getOutcome().isMatch())
                .map(o -> "条件报告：" + o.getOutcome().getMessage())
                .collect(Collectors.joining("；"));
    }

    @Test
    void orderingBetweenAutoConfigurations() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, AaFallbackSinkAutoConfiguration.class))
                .run(ctx -> Facts.record("order.unordered", "兜底配置没有声明 after：MeterSink " + ctx.getBeansOfType(MeterSink.class).size()
                        + " 个 " + ctx.getBeansOfType(MeterSink.class).values().stream().map(MeterSink::name).sorted().toList()));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, OrderedFallbackSinkAutoConfiguration.class))
                .run(ctx -> Facts.record("order.after", "兜底配置声明 after = MetricsAutoConfiguration：MeterSink " + ctx.getBeansOfType(MeterSink.class).size()
                        + " 个 " + ctx.getBeansOfType(MeterSink.class).values().stream().map(MeterSink::name).sorted().toList()));
    }

    @Test
    void realApplicationLoadsFromImportsFile() {
        SpringApplication app = new SpringApplication(ShopApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx = app.run("--payment.endpoint=https://pay.example.test")) {
            boolean payment = ctx.getBeanNamesForType(PaymentClient.class).length == 1;
            boolean internal = ctx.containsBean("internalMarker");
            assertThat(payment).isTrue();
            assertThat(internal).isFalse();
            long evaluated = ConditionEvaluationReport.get(ctx.getBeanFactory()).getConditionAndOutcomesBySource().size();
            Facts.record("app", "主类在 labs.app，支付自动配置在 labs.payment.autoconfigure：PaymentClient 存在=" + payment
                    + "；不在 imports 文件里的 labs.payment.internal.InternalConfig 生效=" + internal
                    + "；条件报告里评估过的来源 " + evaluated + " 个");
        }
    }
}
