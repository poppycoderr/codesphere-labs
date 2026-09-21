package labs.aop;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.RollbackOn;

/** 三种容器配置：类代理、默认配置（有接口时用 JDK 代理）、所有异常都回滚。 */
public final class Configs {
    private Configs() {}

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableAspectJAutoProxy(exposeProxy = true, proxyTargetClass = true)
    @Import({OrderService.class, Repo.class})
    public static class ClassProxy {
        @Bean PlatformTransactionManager txManager() { return new Recorder.TxManager(); }
    }

    @Configuration
    @EnableTransactionManagement
    @Import({OrderService.class, Repo.class})
    public static class Default {
        @Bean PlatformTransactionManager txManager() { return new Recorder.TxManager(); }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true, rollbackOn = RollbackOn.ALL_EXCEPTIONS)
    @Import({OrderService.class, Repo.class})
    public static class RollbackAll {
        @Bean PlatformTransactionManager txManager() { return new Recorder.TxManager(); }
    }
}
