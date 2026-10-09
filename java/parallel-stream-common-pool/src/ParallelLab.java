import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * parallelStream 与不带执行器的 CompletableFuture.supplyAsync 用的是同一个全进程共享的线程池。
 * 标准输出只放确定的结论（线程数、是否落在预期区间），原始耗时写到标准错误，由 verify.sh 归档。
 * 子进程模式：cpus（打印某个 CPU 数下公共池的大小与 supplyAsync 实际用的线程）、clinit（类初始化里用并行流）。
 */
public class ParallelLab {
    static final int BLOCK_MS = 200;
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static void raw(String k, long ms) { System.err.println("timing\t" + k + "\t" + ms + " ms"); }
    static void block() { try { Thread.sleep(BLOCK_MS); } catch (InterruptedException e) { throw new IllegalStateException(e); } }
    static long ms(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }
    static String range(long ms, long lo, long hi) { return "在 " + lo + "–" + hi + " ms 之间 = " + (ms >= lo && ms <= hi); }
    static String kind(String threadName) { return threadName.startsWith("ForkJoinPool.commonPool") ? "公共池线程" : threadName.startsWith("ForkJoinPool-") ? "自建 ForkJoinPool 的线程" : threadName.equals("main") ? "调用线程" : "其他线程（" + threadName.replaceAll("\\d+", "N") + "）"; }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("cpus")) { cpus(); return; }
        if (args.length > 0 && args[0].equals("clinit")) { System.out.println(Holder.SUM); return; }
        out("env", "java.version=" + System.getProperty("java.version"));
        out("pool", "availableProcessors = " + Runtime.getRuntime().availableProcessors() + "，公共池并行度 = " + ForkJoinPool.getCommonPoolParallelism());

        // 一、一个请求里的阻塞调用：8 次各 200 ms
        Set<String> threads = ConcurrentHashMap.newKeySet();
        long t = System.nanoTime();
        IntStream.range(0, 8).parallel().forEach(i -> { threads.add(kind(Thread.currentThread().getName())); block(); });
        long single = ms(t); raw("single.8_calls", single);
        out("single", "8 次 200 ms 的阻塞调用放进并行流：参与执行的有 " + new TreeSet<>(threads) + "；耗时" + range(single, 380, 650));

        // 二、8 个请求同时这样做，每个请求 4 次调用
        t = System.nanoTime();
        IntStream.range(0, 4).parallel().forEach(i -> block());
        long alone = ms(t); raw("concurrent.alone", alone);
        ExecutorService requests = Executors.newFixedThreadPool(8);
        List<Future<Long>> fs = new ArrayList<>();
        for (int r = 0; r < 8; r++) fs.add(requests.submit(() -> { long s = System.nanoTime(); IntStream.range(0, 4).parallel().forEach(i -> block()); return ms(s); }));
        List<Long> each = new ArrayList<>();
        for (Future<Long> f : fs) each.add(f.get());
        Collections.sort(each);
        for (long e : each) raw("concurrent.request", e);
        out("concurrent.alone", "单独一个请求（4 次调用）：耗时" + range(alone, 180, 350));
        out("concurrent.eight", "8 个请求同时进来：超过 550 ms 的不少于 4 个 = " + (each.get(4) >= 550) + "，最慢的" + range(each.get(7), 750, 1100) + "（4 次调用完全串行是 800 ms）");

        // 三、不带执行器的 supplyAsync 用的也是它
        String asyncThread = CompletableFuture.supplyAsync(() -> kind(Thread.currentThread().getName())).get();
        t = System.nanoTime();
        List<CompletableFuture<Void>> cfs = new ArrayList<>();
        for (int i = 0; i < 12; i++) cfs.add(CompletableFuture.runAsync(ParallelLab::block));
        // 池被 12 个阻塞任务占住时，提交一个什么都不做的任务，再跑一个并行流
        long s2 = System.nanoTime();
        CompletableFuture<Long> tiny = CompletableFuture.supplyAsync(() -> ms(s2));
        Set<String> starved = ConcurrentHashMap.newKeySet();
        long s3 = System.nanoTime();
        long sum = IntStream.range(0, 1000).parallel().peek(i -> starved.add(kind(Thread.currentThread().getName()))).asLongStream().sum();
        long streamMs = ms(s3);
        long tinyWait = tiny.get();
        CompletableFuture.allOf(cfs.toArray(new CompletableFuture[0])).get();
        long asyncAll = ms(t);
        raw("async.12_calls_default", asyncAll); raw("async.tiny_task_wait", tinyWait); raw("async.stream_while_saturated", streamMs);
        out("async.thread", "supplyAsync 不传执行器：任务运行在" + asyncThread);
        out("async.default", "12 个 200 ms 的阻塞任务用默认执行器：耗时" + range(asyncAll, 780, 1100));
        out("async.tiny", "这期间提交的一个空任务：等了 600 ms 以上才开始执行 = " + (tinyWait >= 600));
        out("async.stream", "这期间的一个纯计算并行流（1000 个元素求和 = " + sum + "）：参与执行的只有 " + new TreeSet<>(starved) + "，耗时不到 100 ms = " + (streamMs < 100));
        ExecutorService io = Executors.newFixedThreadPool(12);
        t = System.nanoTime(); cfs.clear();
        for (int i = 0; i < 12; i++) cfs.add(CompletableFuture.runAsync(ParallelLab::block, io));
        CompletableFuture.allOf(cfs.toArray(new CompletableFuture[0])).get();
        long dedicated = ms(t); raw("async.12_calls_dedicated", dedicated);
        out("async.dedicated", "同样 12 个任务交给 12 线程的专用线程池：耗时" + range(dedicated, 180, 350));
        io.shutdown(); requests.shutdown();

        // 四、在自建的 ForkJoinPool 里启动并行流
        ForkJoinPool own = new ForkJoinPool(8);
        Set<String> ownThreads = ConcurrentHashMap.newKeySet();
        t = System.nanoTime();
        own.submit(() -> IntStream.range(0, 8).parallel().forEach(i -> { ownThreads.add(kind(Thread.currentThread().getName())); block(); })).get();
        long ownMs = ms(t); raw("own_pool.8_calls", ownMs);
        out("own_pool", "在 new ForkJoinPool(8) 里提交同一个并行流：参与执行的有 " + new TreeSet<>(ownThreads) + "；耗时" + range(ownMs, 180, 350));
        own.shutdown();

        // 五、线程上下文不会跟过去
        ThreadLocal<String> tenant = new ThreadLocal<>();
        tenant.set("tenant-a");
        List<String> seen = IntStream.range(0, 8).parallel().mapToObj(i -> { block(); return kind(Thread.currentThread().getName()) + "读到 " + tenant.get(); }).distinct().sorted().collect(Collectors.toList());
        out("thread_local", "调用线程设置了 ThreadLocal 之后跑并行流：" + seen);
        tenant.remove();

        // 六、往非线程安全的集合里收集
        boolean broken = false;
        for (int round = 0; round < 20 && !broken; round++) {
            List<Integer> target = new ArrayList<>();
            try { IntStream.range(0, 100_000).parallel().forEach(target::add); broken = target.size() != 100_000 || target.contains(null); }
            catch (RuntimeException e) { broken = true; }
        }
        out("shared_list", "并行流里 forEach(list::add) 往 ArrayList 里放 10 万个元素：丢元素、出现 null 或抛异常 = " + broken
                + "；collect(toList()) 得到 " + IntStream.range(0, 100_000).parallel().boxed().collect(Collectors.toList()).size() + " 个");

        // 七、CPU 数不同时公共池的大小
        for (int n : new int[]{1, 2, 4, 8}) out("cpus." + n, child(8, "-XX:ActiveProcessorCount=" + n, "cpus"));

        // 八、类初始化里用并行流
        out("clinit", child(5, "-XX:ActiveProcessorCount=4", "clinit"));
    }

    static void cpus() throws Exception {
        String asyncThread = CompletableFuture.supplyAsync(() -> kind(Thread.currentThread().getName())).get();
        Set<String> names = ConcurrentHashMap.newKeySet();
        long t = System.nanoTime();
        IntStream.range(0, 8).parallel().forEach(i -> { names.add(Thread.currentThread().getName()); block(); });
        long elapsed = ms(t);
        System.err.println("timing\tcpus." + Runtime.getRuntime().availableProcessors() + "\t" + elapsed + " ms");
        System.out.println("公共池并行度 " + ForkJoinPool.getCommonPoolParallelism() + "；8 次阻塞调用的并行流用了 " + names.size() + " 个线程，约 "
                + Math.round(elapsed / (double) BLOCK_MS) + " 轮；supplyAsync 的任务运行在" + asyncThread);
    }

    static final class Holder {
        static final int SUM;
        static { SUM = IntStream.range(0, 100).parallel().map(i -> one() * i).sum(); }
        static int one() { return 1; }
    }

    /** 子进程在 timeoutSeconds 内没有结束就强制终止 */
    static String child(int timeoutSeconds, String jvmArg, String mode) throws Exception {
        Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), jvmArg, "src/ParallelLab.java", mode)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            return "静态初始化块里跑并行流（lambda 调用了本类的静态方法）：" + timeoutSeconds + " 秒内没有结束，被强制终止";
        }
        String s = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        return mode.equals("clinit") ? "静态初始化块里跑并行流：正常结束，结果 " + s : jvmArg + "：" + s;
    }
}
