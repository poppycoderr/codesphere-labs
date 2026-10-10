import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * 运行中改配置：新值「收到了」与「起作用了」之间隔着什么。四个真实的组件各验证一种情况：
 * Spring 的 @Value 字段、ThreadPoolExecutor 的线程数、Logback 的日志级别、HikariCP 的连接池参数。
 */
public class ConfigLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    interface Call { Object run() throws Exception; }
    static String attempt(Call c) { try { return String.valueOf(c.run()); } catch (Exception e) { return "抛出 " + e.getClass().getSimpleName() + "（" + e.getMessage() + "）"; } }

    public static class Limiter {
        @Value("${limit.qps}") int qps;
    }
    public static class Derived {
        final long intervalNanos;
        Derived(int qps) { intervalNanos = 1_000_000_000L / qps; }
    }
    public static class AppConfig {
        @Bean Limiter limiter() { return new Limiter(); }
        @Bean @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE) Limiter freshLimiter() { return new Limiter(); }
        @Bean Derived derived(@Value("${limit.qps}") int qps) { return new Derived(qps); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders() { return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer(); }
    }

    static String pool(ThreadPoolExecutor p) { return "核心 " + p.getCorePoolSize() + "，最大 " + p.getMaximumPoolSize() + "，实际线程 " + p.getPoolSize() + "，排队 " + p.getQueue().size(); }

    public static void main(String[] args) throws Exception {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        lc.reset();                                                            // 去掉默认的控制台输出，Spring 与 HikariCP 的日志不混进结果
        out("env", "java.version=" + System.getProperty("java.version") + " spring=" + org.springframework.core.SpringVersion.getVersion());

        // 一、Spring：Environment 里的值变了，已经注入的字段不变
        Map<String, Object> remote = new HashMap<>(Map.of("limit.qps", "100"));
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("config-center", remote));
            ctx.register(AppConfig.class); ctx.refresh();
            Environment env = ctx.getEnvironment();
            Limiter limiter = ctx.getBean("limiter", Limiter.class);
            remote.put("limit.qps", "500");                                    // 配置中心推来了新值，客户端已经更新了属性源
            out("spring.environment", "属性源里的 limit.qps 从 100 改成 500 之后：Environment.getProperty 读到 " + env.getProperty("limit.qps"));
            out("spring.value_field", "启动时注入的 @Value 字段：" + limiter.qps);
            out("spring.derived", "启动时用这个值算出来的对象（每次间隔的纳秒数）：" + ctx.getBean(Derived.class).intervalNanos + "（按 500 应为 2000000）");
            out("spring.new_bean", "此后新创建的 Bean 注入到的值：" + ctx.getBean("freshLimiter", Limiter.class).qps);
        }

        // 二、线程池：最大线程数改大了，线程没有变多
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocking = () -> { try { release.await(); } catch (InterruptedException ignored) { } };
        ThreadPoolExecutor unbounded = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        for (int i = 0; i < 20; i++) unbounded.execute(blocking);
        out("pool.before", "核心 4、最大 4、无界队列，提交 20 个阻塞任务：" + pool(unbounded));
        unbounded.setMaximumPoolSize(16);
        Thread.sleep(200);
        out("pool.max_only", "把最大线程数改成 16：" + pool(unbounded));
        out("pool.core_over_max", "另一个池（核心 4、最大 4）先把核心线程数改成 16：" + attempt(() -> { ThreadPoolExecutor p = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); try { p.setCorePoolSize(16); return "成功"; } finally { p.shutdown(); } }));
        unbounded.setCorePoolSize(16);
        Thread.sleep(200);
        out("pool.core_raised", "最大线程数已是 16，再把核心线程数改成 16：" + pool(unbounded));
        unbounded.setCorePoolSize(4);
        Thread.sleep(200);
        out("pool.core_lowered_busy", "任务还在执行时把核心线程数改回 4：" + pool(unbounded));
        release.countDown();
        unbounded.setKeepAliveTime(100, TimeUnit.MILLISECONDS);
        while (unbounded.getActiveCount() > 0 || !unbounded.getQueue().isEmpty()) Thread.sleep(20);
        Thread.sleep(600);
        out("pool.core_lowered_idle", "任务全部结束、空闲超过保活时间之后：" + pool(unbounded));
        unbounded.shutdown();
        CountDownLatch release2 = new CountDownLatch(1);
        Runnable blocking2 = () -> { try { release2.await(); } catch (InterruptedException ignored) { } };
        ThreadPoolExecutor bounded = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10));
        for (int i = 0; i < 9; i++) bounded.execute(blocking2);
        bounded.setMaximumPoolSize(8);
        Thread.sleep(200);
        out("pool.bounded_not_full", "核心 4、队列容量 10、已有 5 个在排队，把最大线程数从 4 改成 8：" + pool(bounded));
        for (int i = 0; i < 7; i++) bounded.execute(blocking2);
        Thread.sleep(200);
        out("pool.bounded_full", "再提交 7 个任务（队列满了之后才会加线程）：" + pool(bounded));
        release2.countDown(); bounded.shutdown();

        // 三、日志级别：改了 root，显式配置过级别的 logger 不跟着变
        lc.getLogger("ROOT").setLevel(Level.INFO);
        lc.getLogger("com.shop.pay").setLevel(Level.WARN);                     // 配置文件里单独给支付模块配过级别
        lc.getLogger("ROOT").setLevel(Level.DEBUG);                            // 运行中把 root 调成 DEBUG
        out("logback.root_changed", "运行中把 root 从 INFO 调成 DEBUG：com.shop.order.OrderService 的生效级别 " + lc.getLogger("com.shop.order.OrderService").getEffectiveLevel()
                + "，com.shop.pay.PayService 的生效级别 " + lc.getLogger("com.shop.pay.PayService").getEffectiveLevel());

        // 四、连接池：有的参数运行中能改，有的启动后就封住了
        try (HikariDataSource ds = new HikariDataSource()) {
            ds.setJdbcUrl("jdbc:mysql://127.0.0.1:1/demo"); ds.setUsername("app"); ds.setPassword("example_password");
            ds.setMaximumPoolSize(10); ds.setInitializationFailTimeout(-1); ds.setConnectionTimeout(250);
            attempt(() -> ds.getConnection());                                 // 第一次取连接时连接池才真正启动（这个地址连不上，取连接会失败）
            out("hikari.max_pool_size", "连接池启动后 setMaximumPoolSize(30)：" + attempt(() -> { ds.setMaximumPoolSize(30); return "成功，getMaximumPoolSize = " + ds.getMaximumPoolSize(); }));
            out("hikari.jdbc_url", "连接池启动后 setJdbcUrl(新地址)：" + attempt(() -> { ds.setJdbcUrl("jdbc:mysql://127.0.0.1:2/demo"); return "成功"; }));
            out("hikari.password", "连接池启动后 setPassword(新口令)：" + attempt(() -> { ds.setPassword("example_password_2"); return "成功（只影响之后新建的连接）"; }));
        }
    }
}
