package labs.aop;

import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.SpringVersion;

/** 按文章第三节的顺序打印每种调用方式的事务边界，作为应用日志证据。 */
public class Demo {

    interface Call { void run() throws Exception; }

    static void step(String title, Call c) {
        System.out.println("  - " + title);
        try { c.run(); } catch (Throwable t) { System.out.println("    抛出 " + t.getClass().getSimpleName() + "：" + t.getMessage()); }
    }

    public static void main(String[] args) {
        System.out.println("Spring " + SpringVersion.getVersion() + "，JDK " + Runtime.version());

        System.out.println("\n== 1. 类代理（proxyTargetClass = true）");
        try (var ctx = new AnnotationConfigApplicationContext(Configs.ClassProxy.class)) {
            OrderService s = ctx.getBean(OrderService.class);
            System.out.println("  CGLIB 代理：" + AopUtils.isCglibProxy(s) + "，类名含 $$SpringCGLIB$$：" + s.getClass().getName().contains("$$SpringCGLIB$$"));
            step("外部调用 persist()", s::persist);
            step("create() 内部 this.persist()", s::create);
            step("AopContext.currentProxy().persist()", s::createViaProxy);
            step("final 方法", s::finalMethod);
            step("包级可见方法", s::packagePrivate);
            step("private 方法（经 public 方法调用）", s::callPrivate);
            step("事务方法里切换线程", s::switchThread);
            step("抛出受检异常", s::checked);
            step("抛出运行时异常", s::unchecked);
        }

        System.out.println("\n== 2. 默认配置（proxyTargetClass = false），类实现了接口");
        try (var ctx = new AnnotationConfigApplicationContext(Configs.Default.class)) {
            Object bean = ctx.getBean(OrderApi.class);
            System.out.println("  JDK 代理：" + AopUtils.isJdkDynamicProxy(bean));
            step("按实现类获取 OrderService", () -> ctx.getBean(OrderService.class));
        }

        System.out.println("\n== 3. rollbackOn = ALL_EXCEPTIONS");
        try (var ctx = new AnnotationConfigApplicationContext(Configs.RollbackAll.class)) {
            OrderService s = ctx.getBean(OrderService.class);
            step("抛出受检异常", s::checked);
        }
    }
}
