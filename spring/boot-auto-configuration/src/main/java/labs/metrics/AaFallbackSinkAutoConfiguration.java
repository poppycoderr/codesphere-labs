package labs.metrics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 没有指标输出时兜底一个空实现，但没有声明要排在 MetricsAutoConfiguration 之后。 */
@AutoConfiguration
public class AaFallbackSinkAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(MeterSink.class)
    MeterSink noopSink() {
        return () -> "noop";
    }
}
