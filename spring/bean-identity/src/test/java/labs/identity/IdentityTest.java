package labs.identity;

import static org.assertj.core.api.Assertions.assertThat;

import labs.identity.Parts.Clock;
import labs.identity.Parts.Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class IdentityTest {

    @BeforeEach
    void reset() {
        Parts.calls.clear();
        Parts.clocks.set(0);
    }

    static String same(AnnotationConfigApplicationContext ctx, String... services) {
        Clock bean = ctx.getBean(Clock.class);
        StringBuilder sb = new StringBuilder();
        for (String s : services) {
            sb.append(sb.isEmpty() ? "" : "，").append(s).append(" 里的 Clock 与容器里的是同一个=").append(ctx.getBean(s, Service.class).clock() == bean);
        }
        return sb.toString();
    }

    @Test
    void fullConfiguration() {
        try (var ctx = new AnnotationConfigApplicationContext(Parts.FullConfig.class)) {
            Facts.record("config.full", "@Configuration（默认 full）里 @Bean 方法互相调用：" + same(ctx, "orders", "billing") + "；Clock 构造 " + Parts.clocks.get() + " 次");
            assertThat(Parts.clocks.get()).isEqualTo(1);
        }
    }

    @Test
    void liteConfiguration() {
        try (var ctx = new AnnotationConfigApplicationContext(Parts.LiteConfig.class)) {
            Facts.record("config.lite", "@Configuration(proxyBeanMethods = false) 里同样的调用：" + same(ctx, "orders", "billing") + "；Clock 构造 " + Parts.clocks.get() + " 次");
            assertThat(Parts.clocks.get()).isEqualTo(3);
        }
    }

    @Test
    void beansInComponent() {
        try (var ctx = new AnnotationConfigApplicationContext(Parts.ComponentWithBeans.class)) {
            Facts.record("config.component", "写在 @Component 类里的 @Bean 方法互相调用：" + same(ctx, "orders") + "；Clock 构造 " + Parts.clocks.get() + " 次");
            assertThat(Parts.clocks.get()).isEqualTo(2);
        }
    }

    @Test
    void factoryBean() {
        try (var ctx = new AnnotationConfigApplicationContext(Parts.FactoryConfig.class)) {
            String atRefresh = String.join("、", Parts.calls);
            Parts.calls.clear();
            Object product = ctx.getBean("connection");
            Object factory = ctx.getBean("&connection");
            Object again = ctx.getBean("connection");
            String lookup = String.join("、", Parts.calls);
            Facts.record("factory.names", "getBean(\"connection\") 得到 " + product.getClass().getSimpleName() + "，getBean(\"&connection\") 得到 "
                    + factory.getClass().getSimpleName() + "；isSingleton=false 时两次取产品是同一个=" + (product == again));
            Facts.record("factory.calls", "容器启动时对工厂的调用：" + atRefresh + "；之后取两次产品：" + lookup);
            assertThat(product).isInstanceOf(Parts.Connection.class);
            assertThat(factory).isInstanceOf(Parts.ConnectionFactoryBean.class);
        }
    }

    @Test
    void registeredSingletonSkipsCallbacks() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("viaDefinition", Parts.Managed.class, () -> new Parts.Managed("registerBean 的对象"));
        ctx.refresh();
        ctx.getBeanFactory().registerSingleton("viaSingleton", new Parts.Managed("registerSingleton 的对象"));
        ctx.close();
        Facts.record("register.callbacks", "容器关闭后的回调记录：" + String.join("、", Parts.calls) + "（registerSingleton 交进去的现成对象既不初始化也不销毁）");
        assertThat(Parts.calls).doesNotContain("registerSingleton 的对象 初始化");
    }

    @Test
    void overridingDefaults() {
        try (var ctx = new AnnotationConfigApplicationContext(Parts.FirstDataSource.class, Parts.SecondDataSource.class)) {
            Facts.record("override.framework", "直接用 Spring Framework 的容器，两个配置类都定义了名为 dataSource 的 Bean：启动成功，取到的是「" + ctx.getBean("dataSource") + "」");
        }
        SpringApplication app = new SpringApplication(Parts.FirstDataSource.class, Parts.SecondDataSource.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        String result;
        try (var ctx = app.run()) {
            result = "启动成功，取到「" + ctx.getBean("dataSource") + "」";
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t.getCause() != null && !t.getClass().getSimpleName().equals("BeanDefinitionOverrideException")) {
                t = t.getCause();
            }
            result = "启动失败：" + t.getClass().getSimpleName();
        }
        Facts.record("override.boot", "同样两个配置类用 Spring Boot 启动：" + result);
    }
}
