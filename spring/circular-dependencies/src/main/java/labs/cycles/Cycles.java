package labs.cycles;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

/** 各种循环依赖的最小配置。每组互相引用的两个类放在一个嵌套类里，由测试按需加载。 */
public final class Cycles {
    private Cycles() {
    }

    // ---------- 构造器循环 ----------
    @Component
    public static class CtorOrder {
        public CtorOrder(CtorInventory inventory) {
        }
    }

    @Component
    public static class CtorInventory {
        public CtorInventory(CtorOrder order) {
        }
    }

    @Configuration
    @Import({CtorOrder.class, CtorInventory.class})
    public static class ConstructorCycle {
    }

    // ---------- 字段循环 ----------
    @Component
    public static class FieldOrder {
        @Autowired
        public FieldInventory inventory;
    }

    @Component
    public static class FieldInventory {
        @Autowired
        public FieldOrder order;
    }

    @Configuration
    @Import({FieldOrder.class, FieldInventory.class})
    public static class FieldCycle {
    }

    // ---------- 带 @Transactional 的字段循环 ----------
    @Component
    public static class TxOrder {
        @Autowired
        public TxInventory inventory;

        @Transactional
        public void place() {
        }
    }

    @Component
    public static class TxInventory {
        @Autowired
        public TxOrder order;
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({TxOrder.class, TxInventory.class})
    public static class TransactionalCycle {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new LocalTransactionManager();
        }
    }

    // ---------- 带 @Async 的字段循环 ----------
    @Component
    public static class AsyncOrder {
        public static final java.util.concurrent.atomic.AtomicInteger CONSTRUCTED = new java.util.concurrent.atomic.AtomicInteger();

        @Autowired
        public AsyncInventory inventory;

        public AsyncOrder() {
            CONSTRUCTED.incrementAndGet();
        }

        @Async
        public void notifyWarehouse() {
        }
    }

    @Component
    public static class AsyncInventory {
        public static final java.util.concurrent.atomic.AtomicInteger CONSTRUCTED = new java.util.concurrent.atomic.AtomicInteger();

        @Autowired
        public AsyncOrder order;

        public AsyncInventory() {
            CONSTRUCTED.incrementAndGet();
        }
    }

    @Configuration
    @EnableAsync(proxyTargetClass = true)
    @Import({AsyncOrder.class, AsyncInventory.class})
    public static class AsyncCycle {
    }

    // ---------- prototype 循环 ----------
    @Component
    @Scope("prototype")
    public static class ProtoOrder {
        @Autowired
        public ProtoInventory inventory;
    }

    @Component
    @Scope("prototype")
    public static class ProtoInventory {
        @Autowired
        public ProtoOrder order;
    }

    @Configuration
    @Import({ProtoOrder.class, ProtoInventory.class})
    public static class PrototypeCycle {
    }

    // ---------- 构造器循环 + @Lazy ----------
    @Component
    public static class LazyOrder {
        public final LazyInventory inventory;

        public LazyOrder(@Lazy LazyInventory inventory) {
            this.inventory = inventory;
        }
    }

    @Component
    public static class LazyInventory {
        public final LazyOrder order;

        public LazyInventory(LazyOrder order) {
            this.order = order;
        }

        public String name() {
            return "inventory";
        }
    }

    @Configuration
    @Import({LazyOrder.class, LazyInventory.class})
    public static class LazyConstructorCycle {
    }

    // ---------- 拆出第三个服务之后 ----------
    @Component
    public static class PlainOrder {
    }

    @Component
    public static class PlainInventory {
    }

    /** 下单时需要同时操作订单与库存的流程，由它依赖两者，两者互不依赖。 */
    @Component
    public static class Checkout {
        public final PlainOrder order;
        public final PlainInventory inventory;

        public Checkout(PlainOrder order, PlainInventory inventory) {
            this.order = order;
            this.inventory = inventory;
        }
    }

    @Configuration
    @Import({PlainOrder.class, PlainInventory.class, Checkout.class})
    public static class Extracted {
    }
}
