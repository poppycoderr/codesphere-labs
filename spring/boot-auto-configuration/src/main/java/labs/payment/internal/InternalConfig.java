package labs.payment.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 既不在 imports 文件里、也不在应用的扫描路径里的配置类。 */
@Configuration
public class InternalConfig {
    @Bean
    String internalMarker() {
        return "internal";
    }
}
