import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 「校验 → 幂等 → 执行 → 清理」写成流程骨架时的上下文与失败语义：
 * ThreadLocal 上下文在嵌套、线程复用与异步下的所有权，先查后执行的幂等判断，以及只在正常返回时执行的收尾钩子。
 */
public class Lab {

    record FlowContext(String tenant, String orderId) {}

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    // ---------- 1. ThreadLocal 上下文与嵌套调用 ----------

    static final ThreadLocal<FlowContext> CURRENT = new ThreadLocal<>();

    /** 朴素骨架：开始时 set，结束时 remove。 */
    static <T> T naiveFlow(FlowContext ctx, java.util.function.Supplier<T> body) {
        CURRENT.set(ctx);
        try {
            return body.get();
        } finally {
            CURRENT.remove();
        }
    }

    /** 恢复型骨架：记住进入前的值，退出时恢复。 */
    static <T> T restoringFlow(FlowContext ctx, java.util.function.Supplier<T> body) {
        FlowContext previous = CURRENT.get();
        CURRENT.set(ctx);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static String describe(FlowContext c) {
        return c == null ? "null" : c.tenant() + "/" + c.orderId();
    }

    static void nesting() {
        String seen = naiveFlow(new FlowContext("t1", "A"), () -> {
            naiveFlow(new FlowContext("t1", "B"), () -> "内层处理 B");
            return describe(CURRENT.get());
        });
        out("nest.naive", "外层处理订单 A 时调用了处理订单 B 的流程，内层结束后外层再读上下文：" + seen);

        String seen2 = restoringFlow(new FlowContext("t1", "A"), () -> {
            restoringFlow(new FlowContext("t1", "B"), () -> "内层处理 B");
            return describe(CURRENT.get());
        });
        out("nest.restore", "进入时保存、退出时恢复：内层结束后外层读到 " + seen2);

        String seen3 = explicitOuter(new FlowContext("t1", "A"));
        out("nest.explicit", "上下文作为参数显式传递：内层结束后外层仍持有 " + seen3);
    }

    static String explicitInner(FlowContext ctx) {
        return ctx.orderId();
    }

    static String explicitOuter(FlowContext ctx) {
        explicitInner(new FlowContext(ctx.tenant(), "B"));
        return describe(ctx);
    }

    // ---------- 2. 线程复用：清理没放进 finally ----------

    static void threadReuse() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 第一个任务设置上下文后抛错，清理写在正常路径末尾
            pool.submit(() -> {
                CURRENT.set(new FlowContext("tenant-x", "X1"));
                if (true) {
                    throw new IllegalStateException("库存不足");
                }
                CURRENT.remove();
                return null;
            });
            String leaked = pool.submit(() -> describe(CURRENT.get())).get();
            out("reuse.leak", "同一线程上的下一个任务（没有设置上下文）读到：" + leaked);
            pool.submit(CURRENT::remove).get();

            pool.submit(() -> restoringFlow(new FlowContext("tenant-x", "X2"), () -> {
                throw new IllegalStateException("库存不足");
            }));
            String clean = pool.submit(() -> describe(CURRENT.get())).get();
            out("reuse.finally", "清理放进 finally 后，下一个任务读到：" + clean);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 3. 异步：ThreadLocal 不跟着任务走 ----------

    static void async() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            String inAsync = restoringFlow(new FlowContext("t2", "C"),
                    () -> CompletableFuture.supplyAsync(() -> describe(CURRENT.get()), pool).join());
            out("async.threadlocal", "在流程里 supplyAsync 到线程池，任务里读 ThreadLocal：" + inAsync);
            FlowContext ctx = new FlowContext("t2", "C");
            String passed = CompletableFuture.supplyAsync(() -> describe(ctx), pool).join();
            out("async.explicit", "把上下文作为值捕获进任务：" + passed);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 4. 幂等：先查后执行、随机事件 ID ----------

    static void idempotency() throws Exception {
        // 4.1 两个线程处理同一个命令，都在对方写入之前完成了检查
        Map<String, Boolean> processed = new ConcurrentHashMap<>();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch bothChecked = new CountDownLatch(2);
        Runnable checkThenAct = () -> {
            boolean seen = processed.containsKey("cmd-1");
            bothChecked.countDown();
            try {
                bothChecked.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!seen) {
                executions.incrementAndGet();
                processed.put("cmd-1", true);
            }
        };
        Thread a = new Thread(checkThenAct);
        Thread b = new Thread(checkThenAct);
        a.start();
        b.start();
        a.join();
        b.join();
        out("idem.check-then-act", "两个线程同时处理命令 cmd-1，先查是否处理过再执行：执行了 " + executions.get() + " 次");

        Map<String, Boolean> claimed = new ConcurrentHashMap<>();
        AtomicInteger executions2 = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        Runnable claimThenAct = () -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (claimed.putIfAbsent("cmd-1", true) == null) {
                executions2.incrementAndGet();
            }
        };
        Thread c = new Thread(claimThenAct);
        Thread d = new Thread(claimThenAct);
        c.start();
        d.start();
        go.countDown();
        c.join();
        d.join();
        out("idem.claim", "用一次原子的「占位」代替先查后写（putIfAbsent，相当于唯一约束）：执行了 " + executions2.get() + " 次");

        // 4.2 每次尝试都生成新的事件 ID，去重表认不出同一个命令的重试
        Map<String, Boolean> seenIds = new ConcurrentHashMap<>();
        int charged = 0;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String eventId = UUID.randomUUID().toString();
            if (seenIds.putIfAbsent(eventId, true) == null) {
                charged++;
            }
        }
        out("idem.random-id", "同一个扣款命令因超时重试一次，每次尝试生成新的随机事件 ID：扣款 " + charged + " 次");
        Map<String, Boolean> seenKeys = new ConcurrentHashMap<>();
        int charged2 = 0;
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (seenKeys.putIfAbsent("pay:order-42", true) == null) {
                charged2++;
            }
        }
        out("idem.command-key", "用调用方提供的命令键 pay:order-42 去重：扣款 " + charged2 + " 次");
    }

    // ---------- 5. 收尾钩子只在正常返回时执行 ----------

    static void hooks() {
        Map<String, String> status = new ConcurrentHashMap<>();
        Function<String, String> failing = id -> {
            throw new IllegalStateException("下游超时");
        };
        // 朴素骨架：标记处理中 → 执行 → 正常返回后标记完成
        try {
            status.put("job-1", "处理中");
            failing.apply("job-1");
            status.put("job-1", "完成");
        } catch (RuntimeException e) {
            // 调用方记了日志，骨架没有处理
        }
        out("hook.after-returning", "收尾只写在正常返回之后，执行抛错时任务状态停在：" + status.get("job-1"));

        String outcome = "未知";
        status.put("job-2", "处理中");
        try {
            failing.apply("job-2");
            outcome = "完成";
        } catch (RuntimeException e) {
            outcome = "失败（" + e.getMessage() + "）";
        } finally {
            status.put("job-2", outcome);
        }
        out("hook.finally", "每条退出路径都写终态：任务状态为 " + status.get("job-2"));
    }

    // ---------- 6. 字符串键的上下文 ----------

    static void stringKeys() {
        Map<String, Object> bag = new ConcurrentHashMap<>();
        bag.put("tenantId", "t9");
        Object read = bag.get("tenantID");
        out("bag.typo", "上下文是 Map<String, Object>，写入键 tenantId、读取时写成 tenantID：读到 " + read + "，没有任何报错");
    }

    public static void main(String[] args) throws Exception {
        nesting();
        threadReuse();
        async();
        idempotency();
        hooks();
        stringKeys();
    }
}
