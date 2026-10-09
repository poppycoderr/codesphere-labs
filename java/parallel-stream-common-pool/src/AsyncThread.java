import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/** 用 Java 8 语法写，便于在各个版本上编译运行：打印公共池并行度、不带执行器的 supplyAsync 实际跑在什么线程上，以及 6 个阻塞任务几轮跑完 */
public class AsyncThread {
    public static void main(String[] args) throws Exception {
        String a = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName()).get();
        String b = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName()).get();
        String kind = a.startsWith("ForkJoinPool.commonPool") ? "公共池线程" : "每个任务新建一个线程（两次的线程名 " + a + "、" + b + "）";
        long start = System.nanoTime();
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[6];
        for (int i = 0; i < 6; i++) tasks[i] = CompletableFuture.runAsync(() -> { try { Thread.sleep(200); } catch (InterruptedException e) { throw new IllegalStateException(e); } });
        CompletableFuture.allOf(tasks).get();
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        System.err.println("timing\tjava " + System.getProperty("java.version") + " cpus " + Runtime.getRuntime().availableProcessors() + "\t" + elapsed + " ms");
        kind += "；6 个 200 ms 的阻塞任务约 " + Math.round(elapsed / 200.0) + " 轮跑完";
        System.out.println("java " + System.getProperty("java.version") + "，availableProcessors = " + Runtime.getRuntime().availableProcessors()
                + "，公共池并行度 " + ForkJoinPool.getCommonPoolParallelism() + "：supplyAsync 运行在" + kind);
    }
}
