package labs.lifecycle;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 实现了几乎所有生命周期接口的单例，每个回调都记一笔。refresh() 带 @Transactional，因此它最终会被代理。 */
public class PriceCache implements BeanNameAware, BeanFactoryAware, ApplicationContextAware, InitializingBean,
        DisposableBean, SmartInitializingSingleton, SmartLifecycle {

    public static PriceCache rawInstance;
    public static boolean transactionActiveInPostConstruct;

    private int timeoutMs = 100;
    private boolean running;

    public PriceCache(Clock clock) {
        rawInstance = this;
        Journal.add("PriceCache 构造（注入 Clock）");
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
        Journal.add("属性填充 timeoutMs=" + timeoutMs);
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    @Override
    public void setBeanName(String name) {
        Journal.add("BeanNameAware.setBeanName");
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        Journal.add("BeanFactoryAware.setBeanFactory");
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        Journal.add("ApplicationContextAware.setApplicationContext");
    }

    @PostConstruct
    void postConstruct() {
        Journal.add("@PostConstruct");
        transactionActiveInPostConstruct = refresh();
    }

    @Override
    public void afterPropertiesSet() {
        Journal.add("InitializingBean.afterPropertiesSet");
    }

    public void customInit() {
        Journal.add("@Bean(initMethod)");
    }

    @Override
    public void afterSingletonsInstantiated() {
        Journal.add("SmartInitializingSingleton.afterSingletonsInstantiated");
    }

    @Override
    public void start() {
        running = true;
        Journal.add("SmartLifecycle.start");
    }

    @Override
    public void stop() {
        running = false;
        Journal.add("SmartLifecycle.stop");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 返回调用时是否处在事务中。 */
    @Transactional
    public boolean refresh() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    @PreDestroy
    void preDestroy() {
        Journal.add("@PreDestroy");
    }

    @Override
    public void destroy() {
        Journal.add("DisposableBean.destroy");
    }

    public void customDestroy() {
        Journal.add("@Bean(destroyMethod)");
    }
}
