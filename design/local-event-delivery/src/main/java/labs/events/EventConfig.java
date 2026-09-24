package labs.events;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 异步监听器使用 1 个线程、队列 2 的线程池；未捕获的异步异常只计数。 */
@Configuration
@EnableAsync
@EnableTransactionManagement
public class EventConfig implements AsyncConfigurer {
    public static final AtomicInteger ASYNC_ERRORS = new AtomicInteger();

    @Bean
    PlatformTransactionManager transactionManager() {
        return new LocalTransactionManager();
    }

    @Bean
    RegistrationStore store() {
        return new RegistrationStore();
    }

    @Bean
    Listeners listeners() {
        return new Listeners();
    }

    @Bean
    RegistrationService registrationService(RegistrationStore store, ApplicationEventPublisher publisher) {
        return new RegistrationService(store, publisher);
    }

    @Bean(name = "taskExecutor")
    ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(2);
        ex.setThreadNamePrefix("async-");
        ex.initialize();
        return ex;
    }

    @Override
    public java.util.concurrent.Executor getAsyncExecutor() {
        return taskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> ASYNC_ERRORS.incrementAndGet();
    }
}
