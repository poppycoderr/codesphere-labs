import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class Aqs {
    static long counter;

    /** threads 个线程在 millis 毫秒内反复抢锁，每次持锁做一小段工作；返回每个线程的获取次数 */
    static long[] contend(int threads, long millis, Runnable lock, Runnable unlock) throws Exception {
        long[] counts = new long[threads];
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            int id = i;
            ts[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                while (!stop.get()) {
                    lock.run();
                    try { for (int k = 0; k < 50; k++) counter++; } finally { unlock.run(); }
                    counts[id]++;
                }
            });
            ts[i].start();
        }
        start.countDown(); Thread.sleep(millis); stop.set(true);
        for (Thread t : ts) t.join();
        return counts;
    }
    static void report(String name, long[] c, long millis) {
        long sum = Arrays.stream(c).sum(), min = Arrays.stream(c).min().getAsLong(), max = Arrays.stream(c).max().getAsLong();
        System.out.printf("   %-22s %,13d 次/秒   单线程最少 %,11d  最多 %,11d  最多/最少 %.1f 倍%n", name, sum * 1000 / millis, min, max, (double) max / Math.max(1, min));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("JDK " + Runtime.version() + "，" + Runtime.getRuntime().availableProcessors() + " 核");
        int threads = 8; long ms = 2000;
        System.out.println("\n== 1. " + threads + " 个线程抢同一把锁 " + ms / 1000 + " 秒（各跑两轮，取第二轮）");
        for (int round = 0; round < 2; round++) {
            ReentrantLock unfair = new ReentrantLock(false), fair = new ReentrantLock(true);
            Object mon = new Object();
            long[] a = contend(threads, ms, unfair::lock, unfair::unlock);
            long[] b = contend(threads, ms, fair::lock, fair::unlock);
            long[] c = contend(threads, ms, () -> { /* 用 JNI 无法拆开 synchronized，这里单独测 */ }, () -> {});
            if (round == 1) { report("非公平 ReentrantLock", a, ms); report("公平 ReentrantLock", b, ms); }
        }
        // synchronized 对照
        long[] sc = new long[threads]; AtomicBoolean stop = new AtomicBoolean(); Object mon = new Object();
        for (int round = 0; round < 2; round++) {
            Arrays.fill(sc, 0); stop.set(false);
            Thread[] ts = new Thread[threads]; CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < threads; i++) { int id = i; ts[i] = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                while (!stop.get()) { synchronized (mon) { for (int k = 0; k < 50; k++) counter++; } sc[id]++; } }); ts[i].start(); }
            go.countDown(); Thread.sleep(ms); stop.set(true); for (Thread t : ts) t.join();
        }
        report("synchronized", sc, ms);

        System.out.println("\n== 2. 可重入计数");
        ReentrantLock r = new ReentrantLock();
        r.lock(); r.lock(); r.lock();
        System.out.println("   加锁 3 次后 getHoldCount() = " + r.getHoldCount());
        r.unlock(); r.unlock();
        System.out.println("   释放 2 次后 getHoldCount() = " + r.getHoldCount() + "，isLocked() = " + r.isLocked());
        r.unlock();
        try { r.unlock(); } catch (IllegalMonitorStateException e) { System.out.println("   多释放一次：" + e.getClass().getSimpleName()); }

        System.out.println("\n== 3. Condition 的等待队列与锁的同步队列");
        ReentrantLock lock = new ReentrantLock(); Condition notEmpty = lock.newCondition();
        List<Thread> waiters = new ArrayList<>();
        for (int i = 0; i < 3; i++) { Thread t = new Thread(() -> {
            lock.lock(); try { notEmpty.await(); } catch (InterruptedException e) { } finally { lock.unlock(); } }); t.start(); waiters.add(t); }
        Thread.sleep(300);
        lock.lock();
        System.out.println("   3 个线程 await 之后：条件队列 " + lock.getWaitQueueLength(notEmpty) + "，同步队列 " + lock.getQueueLength());
        notEmpty.signalAll();
        System.out.println("   signalAll 之后、unlock 之前：条件队列 " + lock.getWaitQueueLength(notEmpty) + "，同步队列 " + lock.getQueueLength()
                + "，waiter 状态 " + waiters.get(0).getState());
        lock.unlock();
        for (Thread t : waiters) t.join();
        System.out.println("   unlock 之后：3 个线程依次拿到锁并结束");

        System.out.println("\n== 4. 公平锁上的 tryLock() 会插队");
        // 「插队」的判定：tryLock 成功的那一刻，排队线程仍在同步队列里。
        // 只统计成功次数会混入另一种情况：排队线程已经拿到锁、用完并释放，锁上没人排队了，这与调度快慢有关。
        int trials = 200, ok = 0, barged = 0, okTimed = 0, bargedTimed = 0;
        for (int i = 0; i < trials; i++) {
            ReentrantLock f = new ReentrantLock(true);
            f.lock();
            Thread waiter = new Thread(() -> { f.lock(); f.unlock(); }); waiter.start();
            while (!f.hasQueuedThread(waiter)) Thread.onSpinWait();
            f.unlock();
            if (f.tryLock()) { ok++; if (f.hasQueuedThread(waiter)) barged++; f.unlock(); }
            waiter.join();
            ReentrantLock g = new ReentrantLock(true);
            g.lock();
            Thread w2 = new Thread(() -> { g.lock(); g.unlock(); }); w2.start();
            while (!g.hasQueuedThread(w2)) Thread.onSpinWait();
            g.unlock();
            if (g.tryLock(0, TimeUnit.SECONDS)) { okTimed++; if (g.hasQueuedThread(w2)) bargedTimed++; g.unlock(); }
            w2.join();
        }
        System.out.println("   已有线程排队时，tryLock()                抢到锁 " + ok + " / " + trials + " 次，其中排队线程仍在队列中（插队） " + barged + " 次");
        System.out.println("   已有线程排队时，tryLock(0, SECONDS)      抢到锁 " + okTimed + " / " + trials + " 次，其中排队线程仍在队列中（插队） " + bargedTimed + " 次");
    }
}
