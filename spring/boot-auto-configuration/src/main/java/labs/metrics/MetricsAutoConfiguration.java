package labs.metrics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** 提供真正的指标输出。 */
@AutoConfiguration
public class MetricsAutoConfiguration {
    @Bean
    MeterSink prometheusSink() {
        return () -> "prometheus";
    }
}
