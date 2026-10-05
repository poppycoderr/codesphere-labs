import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * 同一个被测服务（16 个并发处理槽，每次处理 5 毫秒，第 5—7 秒整体卡住），用几种发压方式各测 12 秒，
 * 比较它们记录到的延迟分布、卡顿期间发出的请求数与实际达到的速率。
 */
public class LoadModelLab {
    static final long RUN_MS = 12_000, STALL_FROM_MS = 5_000, STALL_TO_MS = 7_000, SERVICE_MS = 5;

    /** 被测服务：容量 16；卡顿窗口内进入处理的请求要等到窗口结束 */
    static final class Service {
        final Semaphore slots = new Semaphore(16, true);
        final long start;
        final boolean stall;
        Service(long start, boolean stall) { this.start = start; this.stall = stall; }
        void call() throws InterruptedException {
            slots.acquire();
            try {
                long t = (System.nanoTime() - start) / 1_000_000;
                if (stall && t >= STALL_FROM_MS && t < STALL_TO_MS) Thread.sleep(STALL_TO_MS - t);
                Thread.sleep(SERVICE_MS);
            } finally { slots.release(); }
        }
    }

    record Sample(long intendedNs, long sentNs, long doneNs) {}

    static void report(String key, List<Sample> samples, long start, String targetRate, boolean stall) {
        int n = samples.size();
        long[] fromSend = new long[n], fromIntended = new long[n];
        int inStall = 0, slowSend = 0, slowIntended = 0;
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            fromSend[i] = (s.doneNs - s.sentNs) / 1_000_000;
            fromIntended[i] = (s.doneNs - s.intendedNs) / 1_000_000;
            long sentAt = (s.sentNs - start) / 1_000_000;
            if (sentAt >= STALL_FROM_MS && sentAt < STALL_TO_MS) inStall++;
            if (fromSend[i] > 100) slowSend++;
            if (fromIntended[i] > 100) slowIntended++;
        }
        Arrays.sort(fromSend); Arrays.sort(fromIntended);
        System.out.println(key + ".count\t" + n);
        System.out.println(key + ".target_rate\t" + targetRate);
        System.out.println(key + ".achieved_rate\t" + Math.round(n * 1000.0 / RUN_MS));
        if (stall) System.out.println(key + ".sent_during_stall\t" + inStall);
        System.out.println(key + ".from_send\tslow=" + slowSend + "\tp50=" + pct(fromSend, 50) + "\tp90=" + pct(fromSend, 90) + "\tp99=" + pct(fromSend, 99)
                + "\tp999=" + pct(fromSend, 99.9) + "\tmax=" + fromSend[n - 1]);
        System.out.println(key + ".from_intended\tslow=" + slowIntended + "\tp50=" + pct(fromIntended, 50) + "\tp90=" + pct(fromIntended, 90) + "\tp99=" + pct(fromIntended, 99)
                + "\tp999=" + pct(fromIntended, 99.9) + "\tmax=" + fromIntended[n - 1]);
    }

    static long pct(long[] sorted, double p) { return sorted[(int) Math.min(sorted.length - 1, Math.ceil(sorted.length * p / 100.0) - 1)]; }

    /** 闭合模型：固定数量的虚拟用户，收到响应后立刻发下一个 */
    static void closed(int users) throws Exception {
        long start = System.nanoTime(), end = start + RUN_MS * 1_000_000;
        Service svc = new Service(start, true);
        ConcurrentLinkedQueue<Sample> out = new ConcurrentLinkedQueue<>();
        List<Thread> ts = new ArrayList<>();
        for (int u = 0; u < users; u++) ts.add(Thread.ofPlatform().start(() -> {
            try {
                while (System.nanoTime() < end) {
                    long sent = System.nanoTime();
                    svc.call();
                    out.add(new Sample(sent, sent, System.nanoTime()));
                }
            } catch (InterruptedException ignored) { }
        }));
        for (Thread t : ts) t.join();
        report("closed", new ArrayList<>(out), start, "无（由响应速度决定）", true);
    }

    /** 开放模型：按时间表到达，每个请求一个虚拟线程，发压方不会被慢响应挡住 */
    static void open(int ratePerSec) throws Exception {
        long start = System.nanoTime();
        Service svc = new Service(start, true);
        ConcurrentLinkedQueue<Sample> out = new ConcurrentLinkedQueue<>();
        long total = RUN_MS * ratePerSec / 1000, gapNs = 1_000_000_000L / ratePerSec;
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long i = 0; i < total; i++) {
                long intended = start + i * gapNs;
                long wait = intended - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                vt.submit(() -> {
                    long sent = System.nanoTime();
                    try { svc.call(); } catch (InterruptedException ignored) { }
                    out.add(new Sample(intended, sent, System.nanoTime()));
                });
            }
        }
        report("open", new ArrayList<>(out), start, String.valueOf(ratePerSec), true);
    }

    /** 有时间表，但只有一个同步发送线程：上一个请求没回来，下一个就发不出去 */
    static void pacedSingle(int ratePerSec) throws Exception {
        long start = System.nanoTime();
        Service svc = new Service(start, true);
        List<Sample> out = new ArrayList<>();
        long total = RUN_MS * ratePerSec / 1000, gapNs = 1_000_000_000L / ratePerSec;
        for (long i = 0; i < total; i++) {
            long intended = start + i * gapNs;
            long wait = intended - System.nanoTime();
            if (wait > 0) LockSupport.parkNanos(wait);
            long sent = System.nanoTime();
            svc.call();
            out.add(new Sample(intended, sent, System.nanoTime()));
        }
        report("paced_single", out, start, String.valueOf(ratePerSec), true);
    }

    /** 目标速率超过发压方自身的能力：4 个同步发送线程，服务没有卡顿 */
    static void undersized(int ratePerSec, int senders) throws Exception {
        long start = System.nanoTime(), end = start + RUN_MS * 1_000_000;
        Service svc = new Service(start, false);
        ConcurrentLinkedQueue<Sample> out = new ConcurrentLinkedQueue<>();
        AtomicInteger next = new AtomicInteger();
        long gapNs = 1_000_000_000L / ratePerSec;
        List<Thread> ts = new ArrayList<>();
        for (int s = 0; s < senders; s++) ts.add(Thread.ofPlatform().start(() -> {
            try {
                while (true) {
                    long intended = start + next.getAndIncrement() * gapNs;
                    if (intended >= end || System.nanoTime() >= end) return;
                    long wait = intended - System.nanoTime();
                    if (wait > 0) LockSupport.parkNanos(wait);
                    long sent = System.nanoTime();
                    svc.call();
                    out.add(new Sample(intended, sent, System.nanoTime()));
                }
            } catch (InterruptedException ignored) { }
        }));
        for (Thread t : ts) t.join();
        report("undersized", new ArrayList<>(out), start, String.valueOf(ratePerSec), false);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("env\tjava.version=" + System.getProperty("java.version") + " cpus=" + Runtime.getRuntime().availableProcessors());
        closed(10);
        open(1000);
        pacedSingle(100);
        undersized(2000, 4);
    }
}
