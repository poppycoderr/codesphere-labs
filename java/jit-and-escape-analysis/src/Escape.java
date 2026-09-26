import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * 同一个「创建一个点、计算距离平方」的方法，在不同条件下每次调用实际分配多少字节。
 * 用法：java Escape <场景>；每个场景跑 40 批、每批 20 万次调用，输出第 1 批与最后 10 批的每次调用分配字节数。
 * 场景：local（点只在方法内使用）、escape（点写进静态字段）、call（点传给另一个方法）、
 *       bimorphic / megamorphic（点传给接口方法，调用点见过 2 种 / 4 种实现）。
 */
public class Escape {
    record Point(int x, int y) {}

    interface Metric { int apply(Point p); }
    static final class Squared implements Metric { public int apply(Point p) { return p.x() * p.x() + p.y() * p.y(); } }
    static final class Manhattan implements Metric { public int apply(Point p) { return Math.abs(p.x()) + Math.abs(p.y()); } }
    static final class MaxNorm implements Metric { public int apply(Point p) { return Math.max(Math.abs(p.x()), Math.abs(p.y())); } }
    static final class XOnly implements Metric { public int apply(Point p) { return p.x(); } }

    static Point sink;

    static int local(int x, int y) {
        Point p = new Point(x, y);
        return p.x() * p.x() + p.y() * p.y();
    }

    static int escape(int x, int y) {
        Point p = new Point(x, y);
        sink = p;
        return p.x() * p.x() + p.y() * p.y();
    }

    static int consume(Point p) { return p.x() * p.x() + p.y() * p.y(); }

    static int call(int x, int y) { return consume(new Point(x, y)); }

    static int viaMetric(Metric m, int x, int y) { return m.apply(new Point(x, y)); }

    public static void main(String[] args) {
        String scenario = args[0];
        Metric[] metrics = switch (scenario) {
            case "bimorphic" -> new Metric[] {new Squared(), new Manhattan()};
            case "megamorphic" -> new Metric[] {new Squared(), new Manhattan(), new MaxNorm(), new XOnly()};
            default -> new Metric[] {new Squared()};
        };
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        int batch = 200_000;
        List<Double> perCall = new ArrayList<>();
        long checksum = 0;
        for (int b = 0; b < 40; b++) {
            long before = bean.getThreadAllocatedBytes(tid);
            for (int i = 0; i < batch; i++) {
                checksum += switch (scenario) {
                    case "local" -> local(i, b);
                    case "escape" -> escape(i, b);
                    case "call" -> call(i, b);
                    default -> viaMetric(metrics[i % metrics.length], i, b);
                };
            }
            perCall.add((bean.getThreadAllocatedBytes(tid) - before) / (double) batch);
        }
        List<Double> tail = new ArrayList<>(perCall.subList(30, 40));
        Collections.sort(tail);
        String flags = String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(a -> a.startsWith("-X")).toList());
        System.out.printf("%s\t%s%s：第 1 批每次调用分配 %.1f 字节，最后 10 批中位数 %.2f 字节（校验和 %d）%n",
                scenario + (flags.isEmpty() ? "" : " " + flags), scenario, flags.isEmpty() ? "（默认参数）" : "（" + flags + "）", perCall.get(0), tail.get(5), checksum % 1000);
    }
}
