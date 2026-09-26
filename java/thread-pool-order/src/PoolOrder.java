import java.util.concurrent.*;

/** ThreadPoolExecutor 接收任务的顺序：先核心线程，再排队，队列满了才扩到最大线程，最后拒绝 */
public class PoolOrder {
    public static void main(String[] args) {
        var executor = new ThreadPoolExecutor(
                2, 4,                                   // core = 2, max = 4
                30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2),            // 队列容量 2
                Thread.ofPlatform().name("price-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());

        var latch = new CountDownLatch(1);              // 让所有任务都卡住，便于观察
        for (int i = 1; i <= 7; i++) {
            try {
                executor.execute(() -> {
                    try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                System.out.printf("task %d -> poolSize=%d queue=%d%n",
                        i, executor.getPoolSize(), executor.getQueue().size());
            } catch (RejectedExecutionException e) {
                System.out.printf("task %d -> rejected%n", i);
            }
        }
        latch.countDown();
        executor.shutdown();
    }
}
