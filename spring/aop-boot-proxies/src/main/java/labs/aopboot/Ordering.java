package labs.aopboot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 三种切面顺序：默认（两者都不指定 order）、缓存在外层、事务在外层。order 越小越先执行，也就是越靠外。 */
final class Ordering {
    private Ordering() {
    }

    @Configuration
    @ConditionalOnProperty(name = "labs.order", havingValue = "default", matchIfMissing = true)
    @EnableCaching
    static class DefaultOrder {
    }

    @Configuration
    @ConditionalOnProperty(name = "labs.order", havingValue = "cache-outer")
    @EnableCaching(order = 1)
    @EnableTransactionManagement(order = 2)
    static class CacheOuter {
    }

    @Configuration
    @ConditionalOnProperty(name = "labs.order", havingValue = "tx-outer")
    @EnableCaching(order = 2)
    @EnableTransactionManagement(order = 1)
    static class TxOuter {
    }
}
