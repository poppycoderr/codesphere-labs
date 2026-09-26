package labs.lifecycle;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.PriorityOrdered;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 一个后处理器通过构造器依赖普通 Bean，让那个 Bean 在其他后处理器注册之前就被创建出来。 */
@Configuration
@EnableTransactionManagement
public class EarlyConfig {

    /** 带 @Transactional 方法的普通服务。 */
    public static class AuditService {
        @Transactional
        public boolean record() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
    }

    /** 依赖 AuditService 的后处理器，实现了 PriorityOrdered，比负责创建代理的后处理器更早注册。 */
    public static class AuditingPostProcessor implements BeanPostProcessor, PriorityOrdered {
        final AuditService audit;

        public AuditingPostProcessor(AuditService audit) {
            this.audit = audit;
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    @Bean
    static AuditingPostProcessor auditingPostProcessor(AuditService auditService) {
        return new AuditingPostProcessor(auditService);
    }

    @Bean
    static AuditService auditService() {
        return new AuditService();
    }

    @Bean
    PlatformTransactionManager transactionManager() {
        return new LocalTransactionManager();
    }
}
