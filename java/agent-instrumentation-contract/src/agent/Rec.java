package agent;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** advice 内联进被测类后调用的记录器；必须是 public，被测类才能访问。 */
public class Rec {

    public static final AtomicInteger ENTER = new AtomicInteger();
    public static final AtomicInteger EXIT = new AtomicInteger();
    public static final List<String> LOG = new CopyOnWriteArrayList<>();
    public static volatile Throwable thrown;

    public static volatile BlockingQueue<String> queue = new ArrayBlockingQueue<>(5);
    public static volatile boolean blocking;
    public static final AtomicInteger DROPPED = new AtomicInteger();

    public static void reset() {
        ENTER.set(0);
        EXIT.set(0);
        LOG.clear();
        thrown = null;
    }

    /** 上报：阻塞模式用 put，等队列有空位；丢弃模式用 offer，满了就计数返回。 */
    public static void report(String event) throws InterruptedException {
        if (blocking) {
            queue.put(event);
        } else if (!queue.offer(event)) {
            DROPPED.incrementAndGet();
        }
    }

    /** 采集参数：限制长度并遮掉数字。 */
    public static String safeArg(Object arg) {
        String s = String.valueOf(arg);
        String head = (s.length() > 16 ? s.substring(0, 16) : s).replaceAll("[0-9]", "*");
        return s.length() > 16 ? head + "…（共 " + s.length() + " 字符）" : head;
    }
}
