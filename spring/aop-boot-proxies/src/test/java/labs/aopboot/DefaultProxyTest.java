package labs.aopboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Spring Boot 默认配置下的代理类型，以及 @Cacheable、@Async 的自调用。 */
@SpringBootTest
class DefaultProxyTest {
    @Autowired ApplicationContext context;
    @Autowired ProfileService profiles;

    @Test
    void bootUsesClassProxiesByDefault() {
        OrderService byClass = context.getBean(OrderService.class);
        assertTrue(AopUtils.isCglibProxy(byClass));
        Facts.record("proxy.boot", "Spring Boot 默认：按实现类 OrderService 取 Bean 成功，CGLIB 代理=" + AopUtils.isCglibProxy(byClass)
                + "，JDK 代理=" + AopUtils.isJdkDynamicProxy(byClass));
    }

    @Test
    void cacheableSelfInvocationBypassesCache() {
        int before = profiles.loads();
        profiles.nameTwiceViaThis(1);
        int self = profiles.loads() - before;
        profiles.name(1);
        profiles.name(1);
        int external = profiles.loads() - before - self;
        assertEquals(2, self);
        assertEquals(1, external);
        Facts.record("cache.self", "@Cacheable 自调用两次：方法体执行 " + self + " 次；从外部调用两次：执行 " + external + " 次");
    }

    @Test
    void asyncSelfInvocationRunsOnCaller() throws Exception {
        profiles.work();
        TimeUnit.MILLISECONDS.sleep(200);
        String external = profiles.asyncThread();
        profiles.workViaThis();
        String self = profiles.asyncThread();
        assertTrue(external.startsWith("task-"), external);
        assertEquals(Thread.currentThread().getName(), self);
        Facts.record("async.self", "@Async 从外部调用：运行在 " + external.replaceAll("\\d+$", "N") + " 线程；自调用：运行在调用方线程 " + self);
    }
}
