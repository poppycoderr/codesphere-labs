package labs.lifecycle;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 与 Spring Boot 的默认值一致：使用类代理。 */
@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
@Import(LifecycleConfig.class)
public class ClassProxyConfig {
}
