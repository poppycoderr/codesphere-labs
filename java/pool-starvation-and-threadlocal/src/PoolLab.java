import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 线程池上的两个隐蔽问题：父任务在同一个池里等子任务（线程饥饿），以及复用线程上残留的 ThreadLocal。
 */
public class PoolLab {

    static void out(String key, String fact) { System.out.println(key + "\t" + fact); }

    static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
    static final ScopedValue<String> USER = ScopedValue.newInstance();

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));
        starvation("same_pool", Executors.newFixedThreadPool(2), null);
        starvation("child_pool", Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2));
        starvation("virtual", Executors.newVirtualThreadPerTaskExecutor(), null);
        threadLocal();
        System.exit(0);
    }

    /** 2 个父任务各提交 1 个子任务并等待结果。 */
    static void starvation(String key, ExecutorService parentPool, ExecutorService childPoolOrNull) throws Exception {
        ExecutorService childPool = childPoolOrNull == null ? parentPool : childPoolOrNull;
        CyclicBarrier bothParentsRunning = new CyclicBarrier(2);
        List<Future<String>> parents = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            parents.add(parentPool.submit(() -> {
                bothParentsRunning.await();                          // 两个父任务都占住线程之后才提交子任务
                return childPool.submit(() -> "child done").get();
            }));
        }
        int done = 0;
        for (Future<String> p : parents) {
            try { p.get(500, TimeUnit.MILLISECONDS); done++; } catch (TimeoutException ignored) { }
        }
        String detail = "";
        if (done < 2 && parentPool instanceof ThreadPoolExecutor tpe) {
            detail = "；池内活跃线程 " + tpe.getActiveCount() + "，队列里等待的任务 " + tpe.getQueue().size();
        }
        out("starvation." + key, "500ms 内完成的父任务 " + done + "/2" + detail);
        parentPool.shutdownNow();
        childPool.shutdownNow();
    }

    static void threadLocal() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        pool.submit(() -> CURRENT_USER.set("alice")).get();                       // 请求 A：设置后没有清理
        out("threadlocal.leak", "任务 A set(\"alice\") 后不清理，同一线程上的任务 B 读到：" + pool.submit(CURRENT_USER::get).get());

        pool.submit(() -> { CURRENT_USER.set("bob"); try { return null; } finally { CURRENT_USER.remove(); } }).get();
        out("threadlocal.remove", "任务在 finally 里 remove() 后，下一个任务读到：" + pool.submit(CURRENT_USER::get).get());

        pool.submit(() -> ScopedValue.where(USER, "carol").run(() -> { })).get();
        out("scopedvalue", "ScopedValue.where(USER, \"carol\").run(…) 结束后，下一个任务里 USER.isBound()=" + pool.submit(USER::isBound).get());
        pool.shutdownNow();
    }
}
