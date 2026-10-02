import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 超时与取消之后，任务本身是否停下：每个任务持续累加自己的 tick，
 * 在发出超时或取消之后隔一段时间再看 tick 是否还在增长。
 */
public class CancelLab {

    /** 一个可观察的任务：响应中断、吞掉中断，或者根本不检查中断。 */
    static final class Work implements Callable<String> {
        enum Kind { RESPONDS, SWALLOWS, BUSY }
        final Kind kind;
        final long maxMillis;
        final AtomicLong ticks = new AtomicLong();
        final CountDownLatch started = new CountDownLatch(1);
        volatile String exit = "仍在运行";

        Work(Kind kind, long maxMillis) { this.kind = kind; this.maxMillis = maxMillis; }

        @Override public String call() throws Exception {
            started.countDown();
            long end = System.nanoTime() + maxMillis * 1_000_000;
            try {
                while (System.nanoTime() < end) {
                    ticks.incrementAndGet();
                    switch (kind) {
                        case RESPONDS -> Thread.sleep(5);
                        case SWALLOWS -> { try { Thread.sleep(5); } catch (InterruptedException ignored) { } }
                        case BUSY -> { long spin = System.nanoTime() + 5_000_000; while (System.nanoTime() < spin) { Thread.onSpinWait(); } }
                    }
                }
                exit = "跑完全程";
                return "done";
            } catch (InterruptedException e) {
                exit = "被中断后退出";
                throw e;
            }
        }

        /** 观察 200ms 内 tick 是否还在增长。 */
        String observe() throws InterruptedException {
            long before = ticks.get();
            Thread.sleep(200);
            return ticks.get() > before ? "任务仍在运行" : "任务已停止（" + exit + "）";
        }
    }

    static void out(String key, String fact) { System.out.println(key + "\t" + fact); }

    static String outcome(Future<?> f) {
        try {
            f.get(200, TimeUnit.MILLISECONDS);
            return "正常返回";
        } catch (TimeoutException e) {
            return "get 超时（未完成）";
        } catch (CancellationException e) {
            return "CancellationException";
        } catch (ExecutionException e) {
            return "ExecutionException: " + e.getCause().getClass().getSimpleName();
        } catch (InterruptedException e) {
            return "InterruptedException";
        }
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));
        futureGetTimeout();
        futureCancel();
        completableFuture();
        anyOf();
        pendingRegistry();
        shutdown();
        discarded();
        invokeAllTimeout();
        socketRead();
        structuredScope();
        System.exit(0);                       // 实验里故意留下不响应中断的任务，直接结束进程
    }

    static void futureGetTimeout() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Work w = new Work(Work.Kind.RESPONDS, 5_000);
        Future<String> f = pool.submit(w);
        w.started.await();
        String r;
        try { f.get(100, TimeUnit.MILLISECONDS); r = "返回"; } catch (TimeoutException e) { r = "TimeoutException"; }
        out("get_timeout", "get(100ms) 抛出 " + r + "；isDone=" + f.isDone() + "，isCancelled=" + f.isCancelled() + "；" + w.observe());
        f.cancel(true);
        pool.shutdownNow();
    }

    static void futureCancel() throws Exception {
        for (Work.Kind kind : Work.Kind.values()) {
            for (boolean interrupt : new boolean[] {false, true}) {
                if (!interrupt && kind != Work.Kind.RESPONDS) continue;
                ExecutorService pool = Executors.newFixedThreadPool(1);
                Work w = new Work(kind, 3_000);
                Future<String> f = pool.submit(w);
                w.started.await();
                boolean ok = f.cancel(interrupt);
                out("cancel." + kind.name().toLowerCase() + "." + interrupt,
                        "cancel(" + interrupt + ") 返回 " + ok + "；isDone=" + f.isDone() + "，isCancelled=" + f.isCancelled()
                                + "，get 得到 " + outcome(f) + "；" + w.observe());
                pool.shutdownNow();
            }
        }
    }

    static void completableFuture() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Work w = new Work(Work.Kind.RESPONDS, 3_000);
        CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> { try { return w.call(); } catch (Exception e) { throw new CompletionException(e); } }, pool);
        w.started.await();
        boolean ok = cf.cancel(true);
        out("cf.cancel", "CompletableFuture.cancel(true) 返回 " + ok + "；isCancelled=" + cf.isCancelled() + "，get 得到 " + outcome(cf) + "；" + w.observe());

        Work w2 = new Work(Work.Kind.RESPONDS, 3_000);
        CompletableFuture<String> timed = CompletableFuture.supplyAsync(() -> { try { return w2.call(); } catch (Exception e) { throw new CompletionException(e); } }, pool)
                .orTimeout(100, TimeUnit.MILLISECONDS);
        w2.started.await();
        Thread.sleep(200);
        out("cf.orTimeout", "orTimeout(100ms) 之后 get 得到 " + outcome(timed) + "；" + w2.observe());
        pool.shutdownNow();
    }

    static void anyOf() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        Work slow = new Work(Work.Kind.RESPONDS, 3_000);
        CompletableFuture<Object> fast = CompletableFuture.supplyAsync(() -> "fast", pool);
        CompletableFuture<Object> slowCf = CompletableFuture.supplyAsync(() -> { try { return slow.call(); } catch (Exception e) { throw new CompletionException(e); } }, pool);
        Object winner = CompletableFuture.anyOf(fast, slowCf).get(1, TimeUnit.SECONDS);
        slow.started.await();
        out("anyOf.loser", "anyOf 返回 " + winner + "；落选的任务：" + slow.observe());

        CompletableFuture<Object> failsFirst = CompletableFuture.supplyAsync(() -> { throw new IllegalStateException("replica down"); }, pool);
        CompletableFuture<Object> okLater = CompletableFuture.supplyAsync(() -> { try { Thread.sleep(200); } catch (InterruptedException e) { throw new CompletionException(e); } return "ok"; }, pool);
        out("anyOf.failure_first", "一个任务先失败、另一个 200ms 后成功：anyOf 的结果是 " + outcome(CompletableFuture.anyOf(failsFirst, okLater)) + "；后一个任务最终 " + outcome(okLater));
        pool.shutdownNow();
    }

    /** 请求登记表：超时与迟到的响应竞争同一个终态。 */
    static void pendingRegistry() throws Exception {
        Map<Long, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
        CompletableFuture<String> leaky = new CompletableFuture<>();
        pending.put(1L, leaky);
        String r1;
        try { leaky.get(100, TimeUnit.MILLISECONDS); r1 = "返回"; } catch (TimeoutException e) { r1 = "TimeoutException"; }
        out("registry.get_timeout", "只在调用方 get(100ms)：" + r1 + "，登记表里还剩 " + pending.size() + " 项；迟到的响应 complete 返回 " + pending.get(1L).complete("late") + "（没有人再读它）");

        pending.clear();
        CompletableFuture<String> owned = new CompletableFuture<String>().orTimeout(100, TimeUnit.MILLISECONDS);
        pending.put(2L, owned);
        owned.whenComplete((v, e) -> pending.remove(2L, owned));
        Thread.sleep(250);
        CompletableFuture<String> late = pending.get(2L);
        out("registry.or_timeout", "orTimeout + whenComplete 清理：登记表里还剩 " + pending.size() + " 项；迟到的响应" + (late == null ? "找不到登记项，被丢弃" : "仍能找到登记项")
                + "；对已超时的 Future 再 complete 返回 " + owned.complete("late") + "，结果仍是 " + outcome(owned));
    }

    static void shutdown() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Work running = new Work(Work.Kind.RESPONDS, 3_000);
        Work queued = new Work(Work.Kind.RESPONDS, 3_000);
        Future<String> f1 = pool.submit(running);
        Future<String> f2 = pool.submit(queued);
        running.started.await();
        long t = System.nanoTime();
        pool.shutdown();
        long shutdownMs = (System.nanoTime() - t) / 1_000_000;
        boolean terminated = pool.awaitTermination(200, TimeUnit.MILLISECONDS);
        out("shutdown", "shutdown() 用时 " + shutdownMs + "ms 返回；awaitTermination(200ms)=" + terminated + "；运行中的任务：" + running.observe() + "；排队的任务仍会执行");

        List<Runnable> never = pool.shutdownNow();
        Thread.sleep(100);
        out("shutdownNow", "shutdownNow() 返回 " + never.size() + " 个未开始的任务；运行中的任务：" + running.observe()
                + "；运行中任务的 Future：" + outcome(f1) + "；排队任务的 Future：" + outcome(f2) + "，isDone=" + f2.isDone());
        never.forEach(r -> { if (r instanceof Future<?> ft) ft.cancel(false); });
        out("shutdownNow.cancel_returned", "对返回的任务逐个 cancel 之后，排队任务的 Future：" + outcome(f2) + "，isDone=" + f2.isDone());
    }

    /** 被拒绝策略静默丢弃的任务：提交方拿到的 Future 没有人会去完成。 */
    static void discarded() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardPolicy());
        Work running = new Work(Work.Kind.RESPONDS, 1_000);
        pool.submit(running);
        running.started.await();
        pool.submit(new Work(Work.Kind.RESPONDS, 10));
        Future<String> dropped = pool.submit(new Work(Work.Kind.RESPONDS, 10));
        out("discard", "线程 1、队列 1 都占满后再提交，DiscardPolicy 丢弃的任务：" + outcome(dropped) + "，isDone=" + dropped.isDone() + "，isCancelled=" + dropped.isCancelled());
        pool.shutdownNow();
    }

    static void invokeAllTimeout() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Work a = new Work(Work.Kind.RESPONDS, 3_000);
        Work b = new Work(Work.Kind.BUSY, 1_500);
        List<Future<String>> fs = pool.invokeAll(List.of(a, b), 100, TimeUnit.MILLISECONDS);
        out("invokeAll", "invokeAll(…, 100ms) 返回后：响应中断的任务 isCancelled=" + fs.get(0).isCancelled() + "，" + a.observe()
                + "；不检查中断的任务 isCancelled=" + fs.get(1).isCancelled() + "，" + b.observe());
        pool.shutdownNow();
    }

    /** 阻塞在 Socket 读上的线程：平台线程与虚拟线程对中断的反应不同。 */
    static void socketRead() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            for (boolean virtual : new boolean[] {false, true}) {
                Socket client = new Socket("127.0.0.1", server.getLocalPort());
                Socket accepted = server.accept();
                String[] result = {"仍阻塞在 read"};
                Thread t = (virtual ? Thread.ofVirtual() : Thread.ofPlatform().daemon()).unstarted(() -> {
                    try (InputStream in = client.getInputStream()) {
                        int n = in.read();
                        result[0] = "read 返回 " + n;
                    } catch (Exception e) {
                        result[0] = "read 抛出 " + e.getClass().getSimpleName() + "（" + e.getMessage() + "）";
                    }
                });
                t.start();
                Thread.sleep(100);
                t.interrupt();
                t.join(300);
                out("socket." + (virtual ? "virtual" : "platform"), (virtual ? "虚拟线程" : "平台线程") + "阻塞在 Socket 读，interrupt() 300ms 后：" + result[0] + "，线程存活=" + t.isAlive());
                accepted.close();
                t.join(300);
                if (!virtual) out("socket.platform_after_close", "对端关闭连接后：" + result[0]);
            }
        }
    }

    /** 结构化并发（JDK 25 预览）：一个子任务失败后，其余被中断；作用域关闭时等所有子任务真正结束。 */
    static void structuredScope() throws Exception {
        for (Work.Kind kind : new Work.Kind[] {Work.Kind.RESPONDS, Work.Kind.BUSY}) {
            Work sibling = new Work(kind, 1_000);
            long t = System.nanoTime();
            String result;
            try (var scope = StructuredTaskScope.open(Joiner.<String>awaitAllSuccessfulOrThrow())) {
                scope.fork(sibling);
                scope.fork(() -> { sibling.started.await(); Thread.sleep(50); throw new IllegalStateException("subtask failed"); });
                try { scope.join(); result = "join 正常返回"; }
                catch (StructuredTaskScope.FailedException e) { result = "join 抛出 FailedException（" + e.getCause().getClass().getSimpleName() + "）"; }
            }
            long ms = (System.nanoTime() - t) / 1_000_000;
            out("scope." + kind.name().toLowerCase(), result + "；从 fork 到离开 try 块用时 " + ms + "ms；此时兄弟任务：" + sibling.observe());
        }
    }
}
