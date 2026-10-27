import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行时调整 ThreadPoolExecutor：core/max 的先后约束、缩容时的在途与排队任务、
 * afterExecute 对 submit 任务拿到的 Throwable、钩子抛错时工作线程被替换、CallerRunsPolicy 绕过钩子。
 */
public class Lab {

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static ThreadPoolExecutor pool(int core, int max, int queue) {
        return new ThreadPoolExecutor(core, max, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue));
    }

    public static void main(String[] args) throws Exception {
        resizeOrder();
        shrinkWithWork();
        afterExecuteThrowable();
        hookFailure();
        callerRuns();
        desiredVsApplied();
    }

    static String tryRun(Runnable r) {
        try {
            r.run();
            return "成功";
        } catch (IllegalArgumentException e) {
            return "IllegalArgumentException";
        }
    }

    static void resizeOrder() {
        ThreadPoolExecutor up = pool(2, 4, 10);
        String coreFirst = tryRun(() -> up.setCorePoolSize(8));
        String maxThen = tryRun(() -> up.setMaximumPoolSize(16));
        out("resize.up.core-first", "从 core 2 / max 4 扩到 8 / 16，先改 core：setCorePoolSize(8) " + coreFirst + "，再改 max " + maxThen
                + "，结果 core " + up.getCorePoolSize() + " / max " + up.getMaximumPoolSize());
        ThreadPoolExecutor up2 = pool(2, 4, 10);
        up2.setMaximumPoolSize(16);
        up2.setCorePoolSize(8);
        out("resize.up.max-first", "先改 max 再改 core：结果 core " + up2.getCorePoolSize() + " / max " + up2.getMaximumPoolSize());

        ThreadPoolExecutor down = pool(8, 16, 10);
        String maxFirst = tryRun(() -> down.setMaximumPoolSize(4));
        out("resize.down.max-first", "从 8 / 16 缩到 2 / 4，先改 max：setMaximumPoolSize(4) " + maxFirst + "，配置仍是 core " + down.getCorePoolSize() + " / max " + down.getMaximumPoolSize());
        down.setCorePoolSize(2);
        down.setMaximumPoolSize(4);
        out("resize.down.core-first", "先改 core 再改 max：结果 core " + down.getCorePoolSize() + " / max " + down.getMaximumPoolSize());
        up.shutdownNow();
        up2.shutdownNow();
        down.shutdownNow();
    }

    static void shrinkWithWork() throws Exception {
        ThreadPoolExecutor p = pool(4, 4, 20);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            p.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.incrementAndGet();
            });
        }
        Thread.sleep(200);
        int before = p.getPoolSize();
        p.setCorePoolSize(1);
        p.setMaximumPoolSize(1);
        Thread.sleep(200);
        int duringBusy = p.getPoolSize();
        release.countDown();
        p.shutdown();
        p.awaitTermination(5, TimeUnit.SECONDS);
        out("shrink.inflight", "4 个线程都在执行、队列里还有 6 个任务时缩到 1 / 1：调整后立刻看线程数 " + before + " → " + duringBusy
                + "（正在执行的线程不会被打断）；全部结束后完成 " + done.get() + " 个任务（排队的任务没有丢）");
    }

    static void afterExecuteThrowable() throws Exception {
        List<String> seen = new ArrayList<>();
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10)) {
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                synchronized (seen) {
                    seen.add(t == null ? "null" : t.getClass().getSimpleName());
                }
            }
        };
        p.execute(() -> {
            throw new IllegalStateException("execute 的任务失败");
        });
        Future<?> f = p.submit(() -> {
            throw new IllegalStateException("submit 的任务失败");
        });
        Thread.sleep(200);
        String inFuture;
        try {
            f.get();
            inFuture = "无";
        } catch (java.util.concurrent.ExecutionException e) {
            inFuture = e.getCause().getMessage();
        }
        p.shutdown();
        p.awaitTermination(5, TimeUnit.SECONDS);
        out("hook.after-execute", "afterExecute 拿到的 Throwable：execute 提交的任务 " + seen.get(0) + "，submit 提交的任务 " + seen.get(1)
                + "（异常在 Future 里：" + inFuture + "）");
    }

    static void hookFailure() throws Exception {
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        AtomicInteger calls = new AtomicInteger();
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10)) {
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("上报监控失败");
                }
            }
        };
        for (int i = 0; i < 3; i++) {
            p.execute(() -> threadNames.add(Thread.currentThread().getName()));
            Thread.sleep(100);
        }
        p.shutdown();
        p.awaitTermination(5, TimeUnit.SECONDS);
        out("hook.throws", "单线程池里第一次 afterExecute 抛错：3 个任务由 " + threadNames.size() + " 个不同的工作线程执行（抛错的线程退出并被替换），完成的任务数 "
                + p.getCompletedTaskCount());
    }

    static void callerRuns() throws Exception {
        AtomicInteger before = new AtomicInteger();
        Set<String> runners = ConcurrentHashMap.newKeySet();
        CountDownLatch block = new CountDownLatch(1);
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.CallerRunsPolicy()) {
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                before.incrementAndGet();
            }
        };
        p.execute(() -> {
            try {
                block.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        p.execute(() -> {});
        p.execute(() -> runners.add(Thread.currentThread().getName())); // 线程与队列都满，由调用线程执行
        block.countDown();
        p.shutdown();
        p.awaitTermination(5, TimeUnit.SECONDS);
        out("caller-runs", "线程与队列都满时第 3 个任务由 " + runners + " 执行（调用线程），3 个任务里 beforeExecute 只被调用了 " + before.get() + " 次");
    }

    static void desiredVsApplied() {
        ThreadPoolExecutor p = pool(8, 16, 10);
        int desiredCore = 2;
        int desiredMax = 4;
        String applied;
        try {
            p.setMaximumPoolSize(desiredMax);
            p.setCorePoolSize(desiredCore);
            applied = "已生效";
        } catch (IllegalArgumentException e) {
            applied = "未生效（" + e.getClass().getSimpleName() + "）";
        }
        out("config.applied", "配置中心下发 core " + desiredCore + " / max " + desiredMax + "，按「先 max 后 core」直接应用：" + applied
                + "，池的实际配置 core " + p.getCorePoolSize() + " / max " + p.getMaximumPoolSize() + "（期望值与实际值要分别记录）");
        p.shutdownNow();
    }
}
