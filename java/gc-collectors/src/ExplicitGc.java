import java.lang.management.ManagementFactory;

/** 约 400 MB 存活数据的进程调用一次 System.gc()，输出调用前后的 JVM 运行时间和调用耗时。 */
public class ExplicitGc {
    public static void main(String[] args) throws Exception {
        Object[] live = new Object[400 * 1024 * 1024 / 1040];
        for (int i = 0; i < live.length; i++) {
            live[i] = new byte[1000];
        }
        for (int i = 0; i < 3_000_000; i++) {
            live[i % live.length] = new byte[1000];               // 制造一些老年代垃圾
        }
        Thread.sleep(1000);
        long up0 = ManagementFactory.getRuntimeMXBean().getUptime();
        long s = System.nanoTime();
        System.gc();
        long ms = (System.nanoTime() - s) / 1000;
        long up1 = ManagementFactory.getRuntimeMXBean().getUptime();
        System.out.printf("call_from_uptime_ms\t%d%ncall_to_uptime_ms\t%d%ncall_ms\t%.1f%nlive\t%d%n", up0, up1 + 1, ms / 1000.0, live.length);
    }
}
