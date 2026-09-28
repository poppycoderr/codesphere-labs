import java.lang.management.ManagementFactory;
import javax.management.ObjectName;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import sun.misc.Unsafe;

/**
 * 压缩对象头的实际收益，输出为「键<TAB>事实」：
 * 1. 字段布局：第一个字段与数组元素的起始偏移；
 * 2. 单个对象的大小：分配 100 万个实例，全量 GC 后按堆占用的增量折算（先扣掉存放引用的数组）；
 * 3. 一个常见结构：100 万个条目的 HashMap&lt;Long, Order&gt; 占用的堆。
 * 用 Serial GC 运行，System.gc() 触发的是完整的 Full GC，堆占用读数稳定。
 */
public class ObjectSizes {
    static final int N = 1_000_000;

    static class OneInt {
        int a;
    }

    static class TwoInts {
        int a;
        int b;
    }

    static class ThreeInts {
        int a;
        int b;
        int c;
    }

    static class OneLong {
        long a;
    }

    /** 与 HashMap.Node 相同的字段：int hash、三个引用。 */
    static class NodeLike {
        int hash;
        Object key;
        Object value;
        Object next;
    }

    record Order(long id, int quantity, String sku) {
    }

    public static void main(String[] args) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Unsafe u = (Unsafe) f.get(null);
        out("layout.first_field", "OneInt.a 的偏移 %d 字节，OneLong.a 的偏移 %d 字节".formatted(
                u.objectFieldOffset(OneInt.class.getDeclaredField("a")), u.objectFieldOffset(OneLong.class.getDeclaredField("a"))));
        out("layout.array_base", "byte[] 元素起始偏移 %d 字节，long[] 元素起始偏移 %d 字节".formatted(
                u.arrayBaseOffset(byte[].class), u.arrayBaseOffset(long[].class)));

        size("Object", i -> new Object());
        size("Integer", i -> Integer.valueOf(i + 1_000));          // 避开 Integer 缓存
        size("Long", i -> Long.valueOf(i + 1_000L));
        size("OneInt", i -> new OneInt());
        size("TwoInts", i -> new TwoInts());
        size("ThreeInts", i -> new ThreeInts());
        size("NodeLike", i -> new NodeLike());
        size("byte[0]", i -> new byte[0]);
        size("byte[4]", i -> new byte[4]);
        size("byte[8]", i -> new byte[8]);

        String[] parts = {"java.util.HashMap$Node", "java.lang.Long", "ObjectSizes$Order", "java.lang.String", "[B", "[Ljava.util.HashMap$Node;"};
        long[][] before = new long[parts.length][];
        for (int k = 0; k < parts.length; k++) {
            before[k] = histogram(parts[k]);
        }
        long usedBefore = used();
        Map<Long, Order> orders = HashMap.newHashMap(N);
        for (int i = 0; i < N; i++) {
            orders.put((long) i + 1_000, new Order(i, i % 5, "SKU-" + (i % 1_000)));
        }
        long usedAfter = used();
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < parts.length; k++) {
            long[] a = histogram(parts[k]);
            long cnt = a[0] - before[k][0];
            long bytes = a[1] - before[k][1];
            sb.append("%s %,d 个 %.1f MB（每个 %.0f 字节）；".formatted(parts[k], cnt, bytes / 1048576.0, cnt == 0 ? 0.0 : (double) bytes / cnt));
        }
        out("hashmap", "100 万个条目的 HashMap<Long, Order>：堆占用增加 %.1f MB".formatted((usedAfter - usedBefore) / 1048576.0));
        out("hashmap.parts", sb.toString().replaceAll("；$", ""));
        if (orders.size() != N) {
            throw new IllegalStateException();
        }
    }

    static void size(String name, IntFunction<Object> factory) throws Exception {
        String cls = factory.apply(0).getClass().getName();
        long[] before = histogram(cls);
        Object[] keep = new Object[N];
        for (int i = 0; i < N; i++) {
            keep[i] = factory.apply(i);
        }
        long[] after = histogram(cls);
        long count = after[0] - before[0];
        out("size." + name, "%s：新增实例 %,d 个，每个 %.1f 字节".formatted(name, count, (after[1] - before[1]) / (double) count));
        if (keep[N - 1] == null) {
            throw new IllegalStateException();
        }
    }

    /** 用 GC.class_histogram（与 jcmd 相同的数据）取某个类的实例数与总字节数；它会先做一次全量 GC。 */
    static long[] histogram(String className) throws Exception {
        String h = (String) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", new Object[] {new String[0]}, new String[] {String[].class.getName()});
        String jvmName = switch (className) {
            case "[B" -> "[B";
            default -> className;
        };
        for (String line : h.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 4 && p[3].equals(jvmName)) {
                return new long[] {Long.parseLong(p[1]), Long.parseLong(p[2])};
            }
        }
        return new long[] {0, 0};
    }

    static long used() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
