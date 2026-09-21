package labs.aop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 文章「Spring AOP 为什么失效」第三节表格中的每一行，对应这里的一个断言。 */
class ProxyPitfallsTest {

    @Nested
    @DisplayName("类代理（proxyTargetClass = true）")
    class ClassProxy {
        static AnnotationConfigApplicationContext ctx;
        static OrderService s;

        @BeforeAll static void start() { ctx = new AnnotationConfigApplicationContext(Configs.ClassProxy.class); s = ctx.getBean(OrderService.class); }
        @AfterAll static void stop() { ctx.close(); }
        @BeforeEach void reset() { Recorder.reset(); }

        @Test @DisplayName("运行时类型是 CGLIB 子类")
        void cglib() { assertTrue(AopUtils.isCglibProxy(s)); assertTrue(s.getClass().getName().contains("$$SpringCGLIB$$")); }

        @Test @DisplayName("外部调用 persist()：开启并提交事务")
        void external() { s.persist(); assertEquals(List.of("begin", "persist:tx", "commit"), Recorder.EVENTS); }

        @Test @DisplayName("自调用 this.persist()：没有事务")
        void selfInvocation() { s.create(); assertEquals(List.of("create:no-tx", "persist:no-tx"), Recorder.EVENTS); }

        @Test @DisplayName("AopContext.currentProxy()：经过代理，有事务")
        void currentProxy() { s.createViaProxy(); assertEquals(List.of("begin", "persist:tx", "commit"), Recorder.EVENTS); }

        @Test @DisplayName("final 方法：没有事务，且在代理对象上执行，repo 为 null")
        void finalMethod() { s.finalMethod(); assertEquals(List.of("finalMethod:no-tx", "repo=null"), Recorder.EVENTS); }

        @Test @DisplayName("包级可见方法：Spring 6.0 起类代理可以拦截")
        void packagePrivate() { s.packagePrivate(); assertEquals(List.of("begin", "packagePrivate:tx", "commit"), Recorder.EVENTS); }

        @Test @DisplayName("private 方法：不会被拦截")
        void privateMethod() { s.callPrivate(); assertEquals(List.of("privateTx:no-tx"), Recorder.EVENTS); }

        @Test @DisplayName("切换线程：新线程里没有事务")
        void switchThread() throws Exception {
            s.switchThread();
            assertEquals(List.of("begin", "callerThread:tx", "asyncThread:no-tx", "commit"), Recorder.EVENTS);
        }

        @Test @DisplayName("受检异常：默认提交")
        void checkedCommits() {
            assertThrows(Exception.class, s::checked);
            assertEquals(List.of("begin", "commit"), Recorder.EVENTS);
        }

        @Test @DisplayName("运行时异常：回滚")
        void uncheckedRollsBack() {
            assertThrows(IllegalStateException.class, s::unchecked);
            assertEquals(List.of("begin", "rollback"), Recorder.EVENTS);
        }
    }

    @Test @DisplayName("默认配置且类实现了接口：JDK 代理，按实现类取 Bean 失败")
    void jdkProxyByDefault() {
        try (var ctx = new AnnotationConfigApplicationContext(Configs.Default.class)) {
            assertTrue(AopUtils.isJdkDynamicProxy(ctx.getBean(OrderApi.class)));
            assertThrows(NoSuchBeanDefinitionException.class, () -> ctx.getBean(OrderService.class));
        }
    }

    @Test @DisplayName("rollbackOn = ALL_EXCEPTIONS：受检异常也回滚")
    void rollbackOnAllExceptions() {
        try (var ctx = new AnnotationConfigApplicationContext(Configs.RollbackAll.class)) {
            Recorder.reset();
            assertThrows(Exception.class, ctx.getBean(OrderService.class)::checked);
            assertEquals(List.of("begin", "rollback"), Recorder.EVENTS);
        }
    }
}
