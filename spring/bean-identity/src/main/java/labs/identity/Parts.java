package labs.identity;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** 实验用的组件与配置类。 */
public final class Parts {
    private Parts() {
    }

    public static final List<String> calls = new CopyOnWriteArrayList<>();
    public static final AtomicInteger clocks = new AtomicInteger();

    public static final class Clock {
        public Clock() {
            clocks.incrementAndGet();
        }
    }

    public record Service(Clock clock) {}

    @Configuration
    public static class FullConfig {
        @Bean
        Clock clock() {
            return new Clock();
        }

        @Bean
        Service orders() {
            return new Service(clock());
        }

        @Bean
        Service billing() {
            return new Service(clock());
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class LiteConfig {
        @Bean
        Clock clock() {
            return new Clock();
        }

        @Bean
        Service orders() {
            return new Service(clock());
        }

        @Bean
        Service billing() {
            return new Service(clock());
        }
    }

    @Component
    public static class ComponentWithBeans {
        @Bean
        Clock clock() {
            return new Clock();
        }

        @Bean
        Service orders() {
            return new Service(clock());
        }
    }

    public static final class Connection {
        final int n;

        Connection(int n) {
            this.n = n;
        }
    }

    /** 产品与工厂是两个对象。 */
    public static class ConnectionFactoryBean implements FactoryBean<Connection> {
        private final AtomicInteger made = new AtomicInteger();

        public ConnectionFactoryBean() {
            calls.add("工厂构造");
        }

        @Override
        public Connection getObject() {
            calls.add("getObject");
            return new Connection(made.incrementAndGet());
        }

        @Override
        public Class<?> getObjectType() {
            calls.add("getObjectType");
            return Connection.class;
        }

        @Override
        public boolean isSingleton() {
            return false;
        }
    }

    @Configuration
    public static class FactoryConfig {
        @Bean
        ConnectionFactoryBean connection() {
            return new ConnectionFactoryBean();
        }
    }

    /** 有初始化与销毁回调的对象。 */
    public static class Managed implements DisposableBean {
        public final String name;

        public Managed(String name) {
            this.name = name;
        }

        @PostConstruct
        void init() {
            calls.add(name + " 初始化");
        }

        @Override
        public void destroy() {
            calls.add(name + " 销毁");
        }
    }

    @Configuration
    public static class FirstDataSource {
        @Bean
        String dataSource() {
            return "主库";
        }
    }

    @Configuration
    public static class SecondDataSource {
        @Bean
        String dataSource() {
            return "测试库";
        }
    }
}
