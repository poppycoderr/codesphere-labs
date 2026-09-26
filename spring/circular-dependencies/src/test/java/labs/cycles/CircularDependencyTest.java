package labs.cycles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class CircularDependencyTest {

    /** 纯 Spring Framework 容器；allow 为 null 时保持默认值。 */
    static String framework(Class<?> config, Boolean allow) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        if (allow != null) {
            ctx.setAllowCircularReferences(allow);
        }
        ctx.register(config);
        try {
            ctx.refresh();
            ctx.close();
            return "启动成功";
        } catch (RuntimeException e) {
            return "启动失败：" + rootCause(e);
        }
    }

    /** Spring Boot 的 SpringApplication，默认配置。 */
    static String boot(Class<?> config, String... args) {
        SpringApplication app = new SpringApplication(config);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx = app.run(args)) {
            return "启动成功";
        } catch (RuntimeException e) {
            return "启动失败：" + rootCause(e);
        }
    }

    static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + "（" + t.getMessage().replaceAll("\\s+", " ") + "）";
    }

    @Test
    void frameworkVersusBoot() {
        Facts.record("ctor.framework", "构造器循环，Spring Framework 默认：" + framework(Cycles.ConstructorCycle.class, null));
        Facts.record("field.framework", "字段循环，Spring Framework 默认：" + framework(Cycles.FieldCycle.class, null));
        Facts.record("field.framework_disallow", "字段循环，Spring Framework setAllowCircularReferences(false)：" + framework(Cycles.FieldCycle.class, false));
        Facts.record("field.boot", "字段循环，Spring Boot 4.1.1 默认：" + boot(Cycles.FieldCycle.class));
        Facts.record("field.boot_allow", "字段循环，Spring Boot 4.1.1 + spring.main.allow-circular-references=true："
                + boot(Cycles.FieldCycle.class, "--spring.main.allow-circular-references=true"));
        Facts.record("prototype", "prototype 字段循环，Spring Framework 默认：" + prototype());
    }

    static String prototype() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Cycles.PrototypeCycle.class)) {
            ctx.getBean(Cycles.ProtoOrder.class);
            return "获取成功";
        } catch (RuntimeException e) {
            return "getBean 失败：" + rootCause(e);
        }
    }

    @Test
    void proxiesInsideACycle() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Cycles.TransactionalCycle.class)) {
            Cycles.TxOrder order = ctx.getBean(Cycles.TxOrder.class);
            Cycles.TxInventory inventory = ctx.getBean(Cycles.TxInventory.class);
            assertTrue(AopUtils.isAopProxy(order));
            assertSame(order, inventory.order);
            Facts.record("tx", "@Transactional 的 TxOrder 与 TxInventory 字段循环：启动成功；容器里的 TxOrder 是代理="
                    + AopUtils.isAopProxy(order) + "，TxInventory 持有的也是同一个代理=" + (inventory.order == order));
        }
        Cycles.AsyncOrder.CONSTRUCTED.set(0);
        Cycles.AsyncInventory.CONSTRUCTED.set(0);
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Cycles.AsyncCycle.class)) {
            Cycles.AsyncOrder order = ctx.getBean(Cycles.AsyncOrder.class);
            Cycles.AsyncInventory inventory = ctx.getBean(Cycles.AsyncInventory.class);
            Facts.record("async.eager", "@Async 的 AsyncOrder 与 AsyncInventory 字段循环，容器启动时创建：启动成功；AsyncOrder 构造 "
                    + Cycles.AsyncOrder.CONSTRUCTED.get() + " 次，AsyncInventory 构造 " + Cycles.AsyncInventory.CONSTRUCTED.get()
                    + " 次；最终两边持有同一个代理=" + (AopUtils.isAopProxy(order) && inventory.order == order));
        }
        AnnotationConfigApplicationContext lazy = new AnnotationConfigApplicationContext();
        lazy.register(Cycles.AsyncCycle.class);
        lazy.addBeanFactoryPostProcessor(bf -> {
            for (String name : bf.getBeanDefinitionNames()) {
                if (name.startsWith("labs.cycles.Cycles$Async")) {
                    bf.getBeanDefinition(name).setLazyInit(true);
                }
            }
        });
        lazy.refresh();
        String onDemand;
        try {
            lazy.getBean(Cycles.AsyncOrder.class);
            onDemand = "成功";
        } catch (RuntimeException e) {
            onDemand = "失败：" + rootCause(e);
        } finally {
            lazy.close();
        }
        Facts.record("async.on_demand", "同样的循环改为延迟初始化，首次 getBean(AsyncOrder)：" + onDemand);
    }

    @Test
    void lazyAndExtraction() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Cycles.LazyConstructorCycle.class)) {
            Cycles.LazyOrder order = ctx.getBean(Cycles.LazyOrder.class);
            boolean proxy = AopUtils.isAopProxy(order.inventory);
            assertTrue(proxy);
            Facts.record("lazy", "构造器循环，一侧参数加 @Lazy：启动成功；注入的是代理=" + proxy + "，第一次调用时才解析，返回「" + order.inventory.name() + "」");
        }
        String extracted = boot(Cycles.Extracted.class);
        assertFalse(extracted.startsWith("启动失败"));
        Facts.record("extracted", "拆出 Checkout 依赖订单与库存、两者互不依赖，Spring Boot 4.1.1 默认：" + extracted);
    }
}
