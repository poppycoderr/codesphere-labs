package labs.payment.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** payment.* 配置。 */
@ConfigurationProperties("payment")
public record PaymentProperties(
        String endpoint,
        boolean enabled) {
}
