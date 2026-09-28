import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Guava EventBus 的四个行为：订阅者异常被隔离、没有订阅者的事件变成 DeadEvent、默认同步投递、注册关系是强引用。
 * 输出为「键<TAB>事实」。
 */
public class EventBusBehavior {
    record OrderCreated(long id) {
    }

    record NoSubscriber(String x) {
    }

    static class Recorder {
        final String name;
        final List<String> got = new CopyOnWriteArrayList<>();

        Recorder(String name) {
            this.name = name;
        }

        @Subscribe
        public void on(OrderCreated e) {
            got.add(name + "-created-" + e.id());
        }
    }

    static class Throwing {
        @Subscribe
        public void on(OrderCreated e) {
            throw new IllegalStateException("订阅者 B 处理失败");
        }
    }

    static class DeadListener {
        final List<Object> dead = new CopyOnWriteArrayList<>();

        @Subscribe
        public void on(DeadEvent e) {
            dead.add(e.getEvent());
        }
    }

    static class Slow {
        @Subscribe
        public void on(OrderCreated e) throws InterruptedException {
            Thread.sleep(200);
        }
    }

    public static void main(String[] args) throws Exception {
        // 1. 异常隔离
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        EventBus bus = new EventBus((exception, context) -> errors.add(exception));
        Recorder a = new Recorder("A");
        Recorder c = new Recorder("C");
        bus.register(a);
        bus.register(new Throwing());
        bus.register(c);
        String thrown = "无";
        try {
            bus.post(new OrderCreated(1001));
        } catch (RuntimeException e) {
            thrown = e.toString();
        }
        out("isolation", "A 收到 %s，C 收到 %s；交给 SubscriberExceptionHandler 的异常 %d 个（%s）；post 向调用方抛出：%s".formatted(
                a.got, c.got, errors.size(), errors.get(0).getMessage(), thrown));

        // 2. DeadEvent
        DeadListener dl = new DeadListener();
        bus.register(dl);
        bus.post(new NoSubscriber("hello"));
        out("dead", "发布没有订阅者的事件后，DeadEvent 监听器收到 %s".formatted(dl.dead));

        // 3. 同步与异步
        EventBus sync = new EventBus();
        sync.register(new Slow());
        long s = System.nanoTime();
        sync.post(new OrderCreated(1));
        long syncMs = (System.nanoTime() - s) / 1_000_000;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        EventBus async = new AsyncEventBus(pool);
        async.register(new Slow());
        s = System.nanoTime();
        async.post(new OrderCreated(1));
        long asyncMs = (System.nanoTime() - s) / 1_000_000;
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        out("sync", "订阅者 sleep 200 ms：EventBus.post 返回耗时 %d ms，AsyncEventBus.post 返回耗时 %d ms".formatted(syncMs, asyncMs));

        // 4. 强引用
        EventBus leak = new EventBus();
        Recorder tmp = new Recorder("tmp");
        WeakReference<Recorder> ref = new WeakReference<>(tmp);
        leak.register(tmp);
        tmp = null;
        gc();
        boolean aliveWhileRegistered = ref.get() != null;
        leak.unregister(ref.get());
        gc();
        boolean aliveAfterUnregister = ref.get() != null;
        out("leak", "注册后置为 null 并 GC：对象仍然存活=%s；unregister 后再 GC：对象仍然存活=%s".formatted(aliveWhileRegistered, aliveAfterUnregister));
    }

    static void gc() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
        }
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
