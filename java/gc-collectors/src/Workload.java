import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同一份负载比较收集器：一块长期存活数据（持续替换其中一部分，制造老年代垃圾），两个业务线程高速分配短命对象，
 * 一个探针线程每次睡 1 ms、记录实际多等了多久。参数：长期存活数据 MB、测量秒数、替换方式。输出为「键<TAB>值」。
 * 替换方式 fifo 按先后顺序替换最老的一块（像滑动窗口、按时间淘汰的缓存），random 随机替换任意一块。
 * 前 3 秒为预热，不计入吞吐与探针统计。
 */
public class Workload {
    static final int WORKERS = 2;
    static final int WARMUP_MS = 3000;

    public static void main(String[] args) throws Exception {
        int liveMb = Integer.parseInt(args[0]);
        int seconds = Integer.parseInt(args[1]);
        boolean fifo = args[2].equals("fifo");
        java.util.concurrent.atomic.AtomicLong cursor = new java.util.concurrent.atomic.AtomicLong();
        Object[] live = new Object[(int) (liveMb * 1024L * 1024 / 1040)];   // 每个约 1 KB（数组头 + 1000 字节）
        for (int i = 0; i < live.length; i++) {
            live[i] = new byte[1000];
        }
        AtomicBoolean measuring = new AtomicBoolean();
        AtomicBoolean stop = new AtomicBoolean();
        long[] ops = new long[WORKERS];
        Thread[] workers = new Thread[WORKERS];
        for (int w = 0; w < WORKERS; w++) {
            int id = w;
            workers[w] = Thread.ofPlatform().name("worker-" + w).start(() -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                long n = 0;
                long sink = 0;
                while (!stop.get()) {
                    byte[] a = new byte[64 + r.nextInt(448)];
                    a[0] = (byte) n;
                    sink += a.length + a[0];
                    if (n % 200 == 0) {                                  // 替换一块长期存活数据，旧的变成老年代垃圾
                        int slot = fifo ? (int) (cursor.getAndIncrement() % live.length) : r.nextInt(live.length);
                        live[slot] = new byte[1000];
                    }
                    n++;
                    if (measuring.get()) {
                        ops[id]++;
                    }
                }
                if (sink == 42) {
                    System.out.println();
                }
            });
        }
        long[] probe = new long[seconds * 1000 + 1000];
        int[] probes = new int[1];
        Thread prober = Thread.ofPlatform().name("probe").start(() -> {
            while (!stop.get()) {
                long s = System.nanoTime();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
                long extra = System.nanoTime() - s - 1_000_000;
                if (measuring.get() && probes[0] < probe.length) {
                    probe[probes[0]++] = Math.max(0, extra);
                }
            }
        });
        Thread.sleep(WARMUP_MS);
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpu0 = os.getProcessCpuTime();
        long t0 = System.nanoTime();
        measuring.set(true);
        Thread.sleep(seconds * 1000L);
        measuring.set(false);
        long wall = System.nanoTime() - t0;
        long cpu = os.getProcessCpuTime() - cpu0;
        stop.set(true);
        for (Thread t : workers) {
            t.join();
        }
        prober.join();
        long[] p = Arrays.copyOf(probe, probes[0]);
        Arrays.sort(p);
        System.out.printf("measure_from_uptime_ms\t%d%n", ManagementFactory.getRuntimeMXBean().getUptime() - wall / 1_000_000);
        System.out.printf("measure_ms\t%d%n", wall / 1_000_000);
        System.out.printf("ops_per_s\t%d%n", Arrays.stream(ops).sum() * 1_000_000_000L / wall);
        System.out.printf("cpu_cores\t%.2f%n", cpu / (double) wall);
        System.out.printf("probe_p99_ms\t%.2f%n", p[(int) (p.length * 0.99)] / 1e6);
        System.out.printf("probe_p999_ms\t%.2f%n", p[(int) (p.length * 0.999)] / 1e6);
        System.out.printf("probe_max_ms\t%.2f%n", p[p.length - 1] / 1e6);
    }
}
