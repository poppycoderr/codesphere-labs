package labs.payment.autoconfigure;

import labs.payment.sdk.PaymentSdk;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 支付自动配置：SDK 在 classpath 上、没有被关闭、用户没有自己提供客户端时，给出一个默认实现。 */
@AutoConfiguration
@ConditionalOnClass(PaymentSdk.class)
@ConditionalOnProperty(prefix = "payment", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PaymentClient paymentClient(PaymentProperties properties) {
        return () -> "默认 HTTP 客户端 → " + properties.endpoint() + "（" + PaymentSdk.version() + "）";
    }
}
