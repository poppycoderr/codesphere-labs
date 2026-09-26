package labs.aopboot;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TxManagerConfig {
    @Bean
    CountingTransactionManager transactionManager(DataSource dataSource) {
        return new CountingTransactionManager(dataSource);
    }
}
