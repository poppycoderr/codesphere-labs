package labs.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LifecycleTest {

    @Test
    void singletonCallbackOrder() {
        Journal.drain();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(LifecycleConfig.class);
        List<String> startup = Journal.drain();
        context.close();
        List<String> shutdown = Journal.drain();
        Facts.record("order.startup", String.join(" → ", startup));
        Facts.record("order.shutdown", String.join(" → ", shutdown));
        assertEquals("BeanFactoryPostProcessor 修改 priceCache 的定义：timeoutMs=500", startup.getFirst());
        assertTrue(startup.indexOf("@PostConstruct") < startup.indexOf("InitializingBean.afterPropertiesSet"));
        assertTrue(startup.indexOf("InitializingBean.afterPropertiesSet") < startup.indexOf("@Bean(initMethod)"));
    }

    @Test
    void jdkProxyByDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(LifecycleConfig.class)) {
            Object bean = context.getBean("priceCache");
            String byType;
            try {
                context.getBean(PriceCache.class);
                byType = "找到";
            } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e) {
                byType = "NoSuchBeanDefinitionException";
            }
            assertTrue(java.lang.reflect.Proxy.isProxyClass(bean.getClass()));
            Facts.record("proxy.jdk", "@EnableTransactionManagement 默认：priceCache 是 JDK 动态代理=" + java.lang.reflect.Proxy.isProxyClass(bean.getClass())
                    + "，按类型 getBean(PriceCache.class)：" + byType);
        }
    }

    @Test
    void containerHandsOutTheProxy() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ClassProxyConfig.class)) {
            PriceCache fromContainer = context.getBean(PriceCache.class);
            assertNotSame(PriceCache.rawInstance, fromContainer);
            assertFalse(PriceCache.transactionActiveInPostConstruct);
            assertTrue(fromContainer.refresh());
            Facts.record("proxy", "getBean 返回 " + fromContainer.getClass().getSimpleName().replaceAll("\\$\\$SpringCGLIB\\$\\$\\d+", "\\$\\$SpringCGLIB\\$\\$<n>")
                    + "，与构造出的实例相同=" + (PriceCache.rawInstance == fromContainer)
                    + "；@PostConstruct 里调用 @Transactional 方法：事务活跃=" + PriceCache.transactionActiveInPostConstruct
                    + "；启动后经容器取得的对象调用：事务活跃=" + fromContainer.refresh()
                    + "；timeoutMs=" + fromContainer.timeoutMs());
        }
    }

    @Test
    void prototypeIsNotDestroyed() {
        Journal.drain();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(LifecycleConfig.class);
        Journal.drain();
        context.getBean(ProtoResource.class);
        context.getBean(ProtoResource.class);
        context.close();
        List<String> steps = Journal.drain().stream().filter(s -> s.startsWith("ProtoResource")).toList();
        long inits = steps.stream().filter(s -> s.endsWith("@PostConstruct")).count();
        long destroys = steps.stream().filter(s -> s.endsWith("@PreDestroy")).count();
        assertEquals(2, inits);
        assertEquals(0, destroys);
        Facts.record("prototype", "取 2 次 prototype Bean 后关闭容器：@PostConstruct " + inits + " 次，@PreDestroy " + destroys + " 次");
    }

    @Test
    void earlyCreatedBeanMissesProxying() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EarlyConfig.class)) {
            EarlyConfig.AuditService audit = context.getBean(EarlyConfig.AuditService.class);
            boolean proxied = audit.getClass().getName().contains("$$");
            boolean tx = audit.record();
            assertFalse(proxied);
            assertFalse(tx);
            Facts.record("early", "被后处理器提前创建的 AuditService：是代理=" + proxied + "，调用 @Transactional 方法时事务活跃=" + tx);
        }
    }
}
