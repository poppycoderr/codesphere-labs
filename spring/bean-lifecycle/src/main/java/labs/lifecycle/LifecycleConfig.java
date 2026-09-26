package labs.lifecycle;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 生命周期实验的配置。 */
@Configuration
@EnableTransactionManagement
public class LifecycleConfig {


    @Bean
    static BeanFactoryPostProcessor timeoutOverride() {
        return beanFactory -> {
            Journal.add("BeanFactoryPostProcessor 修改 priceCache 的定义：timeoutMs=500");
            beanFactory.getBeanDefinition("priceCache").getPropertyValues().add("timeoutMs", 500);
        };
    }

    @Bean
    static BeanPostProcessor tracingPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) {
                if (name.equals("priceCache")) {
                    Journal.add("BeanPostProcessor.before");
                }
                return bean;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (name.equals("priceCache")) {
                    Journal.add("BeanPostProcessor.after（拿到的是" + (org.springframework.aop.support.AopUtils.isAopProxy(bean) ? "代理" : "原始对象") + "）");
                }
                return bean;
            }
        };
    }

    @Bean
    Clock clock() {
        return new Clock();
    }

    @Bean(initMethod = "customInit", destroyMethod = "customDestroy")
    PriceCache priceCache(Clock clock) {
        return new PriceCache(clock);
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    ProtoResource protoResource() {
        return new ProtoResource();
    }

    @Bean
    PlatformTransactionManager transactionManager() {
        return new LocalTransactionManager();
    }

    @Bean
    ApplicationListener<ContextRefreshedEvent> refreshed() {
        return event -> Journal.add("ContextRefreshedEvent");
    }

    @Bean
    ApplicationListener<ContextClosedEvent> closed() {
        return event -> Journal.add("ContextClosedEvent");
    }
}
