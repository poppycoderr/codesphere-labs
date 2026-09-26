import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 两组对照：
 * throughput：阻塞 50ms 的任务交给 200 线程的平台线程池与虚拟线程，以及只有 20 个「连接」的虚拟线程；
 * pinning：1000 个虚拟线程各自进入独立的 synchronized 块或 ReentrantLock，在里面阻塞 50ms。
 */
public class VT {
    static void io() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static long run(ExecutorService pool, int tasks, Runnable body) {
        long t0 = System.nanoTime();
        try (pool) {                                   // close() 会等待全部任务结束
            for (int i = 0; i < tasks; i++) pool.submit(body);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    public static void main(String[] args) {
        int cpus = Runtime.getRuntime().availableProcessors();
        System.out.println("availableProcessors\t" + cpus);
        if (args[0].equals("throughput")) {
            run(Executors.newVirtualThreadPerTaskExecutor(), 1000, VT::io);   // 预热
            System.out.println("platform-200-5000\t" + run(Executors.newFixedThreadPool(200), 5000, VT::io));
            System.out.println("virtual-5000\t" + run(Executors.newVirtualThreadPerTaskExecutor(), 5000, VT::io));
            Semaphore db = new Semaphore(20);
            System.out.println("virtual-20conn-1000\t" + run(Executors.newVirtualThreadPerTaskExecutor(), 1000, () -> {
                try {
                    db.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    io();
                } finally {
                    db.release();
                }
            }));
        } else {
            System.out.println("synchronized-1000\t" + run(Executors.newVirtualThreadPerTaskExecutor(), 1000, () -> {
                Object monitor = new Object();               // 每个任务一把独立的锁，没有竞争
                synchronized (monitor) {
                    io();
                }
            }));
            System.out.println("reentrantlock-1000\t" + run(Executors.newVirtualThreadPerTaskExecutor(), 1000, () -> {
                ReentrantLock lock = new ReentrantLock();
                lock.lock();
                try {
                    io();
                } finally {
                    lock.unlock();
                }
            }));
        }
    }
}
