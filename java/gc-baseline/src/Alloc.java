import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/**
 * 分配密集的负载：每轮分配一批短命的小对象，其中 1% 放进一个固定大小的环形缓冲区里存活较长时间（老年代稳定，不会 OOM）。
 * 参数：运行秒数。结束时输出本线程分配的总字节数，供计算分配速率。
 */
public class Alloc {
    public static void main(String[] args) throws Exception {
        long seconds = Long.parseLong(args[0]);
        Object[] retained = new Object[200_000];                 // 约 200 MB 上限以内的长期存活对象（每个约 1 KB）
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long i = 0;
        long sink = 0;
        while (System.nanoTime() < end) {
            for (int k = 0; k < 10_000; k++, i++) {
                byte[] b = new byte[64 + (int) (i % 192)];
                sink += b.length;
                if (i % 100 == 0) {
                    retained[(int) ((i / 100) % retained.length)] = new byte[1024];
                }
            }
        }
        ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long bytes = mx.getThreadAllocatedBytes(Thread.currentThread().threadId());
        System.out.printf("allocated_mb\t%d%nrate_mb_per_s\t%d%nsink\t%d%n", bytes >> 20, (bytes >> 20) / seconds, sink % 7);
    }
}
