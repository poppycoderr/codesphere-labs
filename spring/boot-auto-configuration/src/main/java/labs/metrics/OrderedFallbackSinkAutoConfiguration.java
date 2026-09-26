package labs.metrics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 同样的兜底，声明排在 MetricsAutoConfiguration 之后。 */
@AutoConfiguration(after = MetricsAutoConfiguration.class)
public class OrderedFallbackSinkAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(MeterSink.class)
    MeterSink noopSink() {
        return () -> "noop";
    }
}
