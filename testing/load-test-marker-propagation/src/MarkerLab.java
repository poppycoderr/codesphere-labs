import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;

/**
 * 压测标记的传播：入口把「这是压测流量」记在当前线程上，订单写入处按标记决定写正式表还是影子表。
 * 逐个场景看标记在线程池、异步回调、定时任务、消息、缓存这些边界上是否还在。每个场景单独建线程池，顺序执行，输出确定。
 */
public class MarkerLab {
    enum Traffic { NORMAL, SHADOW }

    static final ThreadLocal<Traffic> CTX = new ThreadLocal<>();
    static final InheritableThreadLocal<Traffic> INHERITED = new InheritableThreadLocal<>();
    static final ScopedValue<Traffic> SCOPED = ScopedValue.newInstance();

    /** 订单写入处：记录每一笔写到了哪张表 */
    static final class OrderStore {
        final List<String> real = new ArrayList<>(), shadow = new ArrayList<>();
        synchronized void save(String id, Traffic seen) {
            (seen == Traffic.SHADOW ? shadow : real).add(id);
        }
        /** 严格模式：没有明确的流量类型就拒绝写入 */
        synchronized void saveStrict(String id, Traffic seen) {
            if (seen == null) throw new IllegalStateException("traffic context missing");
            save(id, seen);
        }
        String where(String id) { return real.contains(id) ? "正式表" : shadow.contains(id) ? "影子表" : "没有写入"; }
    }

    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static Traffic scoped() { return SCOPED.isBound() ? SCOPED.get() : null; }

    static String seen(Traffic t) { return t == null ? "无标记" : t.name(); }

    /** 提交时捕获当前线程的标记，执行时恢复，执行完清掉 */
    static Runnable propagate(Runnable task) {
        Traffic captured = CTX.get();
        return () -> {
            Traffic before = CTX.get();
            CTX.set(captured);
            try { task.run(); } finally { if (before == null) CTX.remove(); else CTX.set(before); }
        };
    }

    record Message(String orderId, Map<String, String> headers) {}

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 1. 同一个线程里
        OrderStore s1 = new OrderStore();
        CTX.set(Traffic.SHADOW);
        s1.save("o-1", CTX.get());
        CTX.remove();
        out("same_thread", "压测请求在入口线程里直接写订单：" + s1.where("o-1"));

        // 2. 线程池
        OrderStore s2 = new OrderStore();
        Traffic[] saw = new Traffic[1];
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            CTX.set(Traffic.SHADOW);
            pool.submit(() -> { saw[0] = CTX.get(); s2.save("o-2", CTX.get()); }).get();
            CTX.remove();
        }
        out("thread_pool", "压测请求把写订单交给线程池：工作线程看到 " + seen(saw[0]) + "，订单写进 " + s2.where("o-2"));

        // 3. CompletableFuture 的默认执行器
        OrderStore s3 = new OrderStore();
        CTX.set(Traffic.SHADOW);
        Traffic t3 = CompletableFuture.supplyAsync(() -> { Traffic t = CTX.get(); s3.save("o-3", t); return t; }).get();
        CTX.remove();
        out("completable_future", "supplyAsync 里写订单：看到 " + seen(t3) + "，订单写进 " + s3.where("o-3"));

        // 4. 定时任务（延迟重试）
        OrderStore s4 = new OrderStore();
        try (ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor()) {
            CTX.set(Traffic.SHADOW);
            timer.schedule(() -> s4.save("o-4", CTX.get()), 20, TimeUnit.MILLISECONDS).get();
            CTX.remove();
        }
        out("scheduled_retry", "压测请求失败后 20 毫秒重试：订单写进 " + s4.where("o-4"));

        // 5. InheritableThreadLocal 加线程池：工作线程在第一次提交时创建，继承了当时的标记，之后一直带着
        OrderStore s5 = new OrderStore();
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            INHERITED.set(Traffic.SHADOW);
            pool.submit(() -> s5.save("shadow-order", INHERITED.get())).get();
            INHERITED.remove();
            // 之后来了一个正常请求，入口没有设置任何标记
            pool.submit(() -> s5.save("real-order", INHERITED.get())).get();
        }
        out("inheritable.shadow_first", "压测请求先到，工作线程由它创建：压测订单写进 " + s5.where("shadow-order") + "；随后的正常订单写进 " + s5.where("real-order"));
        OrderStore s5b = new OrderStore();
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            pool.submit(() -> s5b.save("real-order", INHERITED.get())).get();
            INHERITED.set(Traffic.SHADOW);
            pool.submit(() -> s5b.save("shadow-order", INHERITED.get())).get();
            INHERITED.remove();
        }
        out("inheritable.normal_first", "正常请求先到，工作线程由它创建：正常订单写进 " + s5b.where("real-order") + "；随后的压测订单写进 " + s5b.where("shadow-order"));

        // 6. 消息：消费者在另一个线程（真实系统里是另一个进程）
        OrderStore s6 = new OrderStore();
        BlockingQueue<Message> queue = new ArrayBlockingQueue<>(4);
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 2; i++) {
                    Message m = queue.take();
                    String h = m.headers().get("x-traffic");
                    s6.save(m.orderId(), h == null ? CTX.get() : Traffic.valueOf(h));
                }
            } catch (InterruptedException ignored) { }
        });
        CTX.set(Traffic.SHADOW);
        queue.put(new Message("m-no-header", Map.of()));
        queue.put(new Message("m-with-header", Map.of("x-traffic", CTX.get().name())));
        CTX.remove();
        consumer.join();
        out("message", "压测请求发出两条消息：不带头的那条，消费者把订单写进 " + s6.where("m-no-header") + "；带 x-traffic 头的那条写进 " + s6.where("m-with-header"));

        // 7. 缓存：压测与正常流量共用键
        Map<String, Integer> cache = new HashMap<>();
        cache.put("price:sku-1", 1);                                  // 压测请求把压测用的价格 1 写进缓存
        int normalReads = cache.getOrDefault("price:sku-1", 9900);     // 正常请求读到的价格（真实价格 9900）
        Map<String, Integer> cache2 = new HashMap<>();
        cache2.put("shadow:price:sku-1", 1);
        int isolatedReads = cache2.getOrDefault("price:sku-1", 9900);
        out("cache", "共用键：正常请求读到价格 " + normalReads + "；压测流量的键加前缀：正常请求读到价格 " + isolatedReads);

        // 8. 提交时包装任务
        OrderStore s8 = new OrderStore();
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            CTX.set(Traffic.SHADOW);
            pool.submit(propagate(() -> s8.save("shadow-order", CTX.get()))).get();
            CTX.remove();
            pool.submit(propagate(() -> s8.save("real-order", CTX.get()))).get();
            Traffic[] left = new Traffic[1];
            pool.submit(() -> left[0] = CTX.get()).get();
            out("wrapped", "提交时捕获、执行后清理：压测订单写进 " + s8.where("shadow-order") + "，随后的正常订单写进 " + s8.where("real-order")
                    + "，工作线程上残留的标记是 " + seen(left[0]));
        }

        // 9. ScopedValue：只在 where(...).run(...) 的范围内可见
        OrderStore s9 = new OrderStore();
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            Traffic[] inPool = new Traffic[1], inFork = new Traffic[1], after = new Traffic[1];
            ScopedValue.where(SCOPED, Traffic.SHADOW).run(() -> {
                s9.save("in-scope", scoped());
                try {
                    pool.submit(() -> inPool[0] = scoped()).get();
                    try (var scope = StructuredTaskScope.open()) {
                        var f = scope.fork(() -> scoped());
                        scope.join();
                        inFork[0] = f.get();
                    }
                } catch (Exception e) { throw new IllegalStateException(e); }
            });
            after[0] = scoped();
            out("scoped_value", "范围内写订单：" + s9.where("in-scope") + "；范围内提交给线程池的任务看到 " + seen(inPool[0])
                    + "；StructuredTaskScope 分叉出的子任务看到 " + seen(inFork[0]) + "；范围结束后同一线程看到 " + seen(after[0]));
        }

        // 10. 严格模式：入口必须明确设置 NORMAL 或 SHADOW，写入处遇到「无标记」就拒绝
        OrderStore s10 = new OrderStore();
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            CTX.set(Traffic.SHADOW);
            String result;
            try { pool.submit(() -> s10.saveStrict("o-10", CTX.get())).get(); result = "写入成功"; }
            catch (Exception e) { result = "抛出 " + e.getCause().getClass().getSimpleName() + "（" + e.getCause().getMessage() + "）"; }
            CTX.set(Traffic.NORMAL);
            s10.saveStrict("o-11", CTX.get());
            CTX.remove();
            out("strict", "标记在线程池里丢失时：" + result + "，订单 " + s10.where("o-10") + "；入口明确标为 NORMAL 的请求：订单写进 " + s10.where("o-11"));
        }
    }
}
