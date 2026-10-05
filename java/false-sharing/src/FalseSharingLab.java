import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLongArray;

/** 伪共享：两个线程各写各的变量，变量之间的距离不同时每次写入的耗时。两线程的场景每种跑 5 轮取中位数，单线程基准取最快的一轮。 */
public class FalseSharingLab {
    static final long OPS = 100_000_000L;

    static class Adjacent { volatile long a; volatile long b; }

    static class Pad56L { long p1, p2, p3, p4, p5, p6, p7; }
    static class Pad56V extends Pad56L { volatile long a; }
    static class Pad56M extends Pad56V { long q1, q2, q3, q4, q5, q6, q7; }
    static class Padded56 extends Pad56M { volatile long b; }

    static class Pad120L { long p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15; }
    static class Pad120V extends Pad120L { volatile long a; }
    static class Pad120M extends Pad120V { long q1, q2, q3, q4, q5, q6, q7, q8, q9, q10, q11, q12, q13, q14, q15; }
    static class Padded120 extends Pad120M { volatile long b; }

    static class Annotated {
        @jdk.internal.vm.annotation.Contended volatile long a;
        @jdk.internal.vm.annotation.Contended volatile long b;
    }

    static class Plain { long a; long b; }

    static final VarHandle AA, AB, PA56, PB56, PA120, PB120, CA, CB;
    static {
        try {
            var l = MethodHandles.lookup();
            AA = l.findVarHandle(Adjacent.class, "a", long.class); AB = l.findVarHandle(Adjacent.class, "b", long.class);
            PA56 = l.findVarHandle(Pad56V.class, "a", long.class); PB56 = l.findVarHandle(Padded56.class, "b", long.class);
            PA120 = l.findVarHandle(Pad120V.class, "a", long.class); PB120 = l.findVarHandle(Padded120.class, "b", long.class);
            CA = l.findVarHandle(Annotated.class, "a", long.class); CB = l.findVarHandle(Annotated.class, "b", long.class);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    @SuppressWarnings({"deprecation", "removal"})
    static long offset(Class<?> c, String name) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        Class<?> k = c; Field f = null;
        while (f == null) { try { f = k.getDeclaredField(name); } catch (NoSuchFieldException e) { k = k.getSuperclass(); } }
        return u.objectFieldOffset(f);
    }

    /** 两个线程同时开始，各自执行自己的循环；每轮取两个线程里较慢那个的每次操作纳秒数，返回 5 轮的中位数 */
    static double race(Runnable r1, Runnable r2) throws Exception {
        double[] rounds = new double[5];
        for (int round = 0; round < 5; round++) {
            CountDownLatch go = new CountDownLatch(1);
            long[] t = new long[2];
            Thread a = new Thread(() -> { await(go); long s = System.nanoTime(); r1.run(); t[0] = System.nanoTime() - s; });
            Thread b = new Thread(() -> { await(go); long s = System.nanoTime(); r2.run(); t[1] = System.nanoTime() - s; });
            a.start(); b.start(); go.countDown(); a.join(); b.join();
            rounds[round] = Math.max(t[0], t[1]) / (double) OPS;
        }
        java.util.Arrays.sort(rounds);
        return rounds[2];
    }

    static double solo(Runnable r) throws Exception {
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long[] t = new long[1];
            Thread a = new Thread(() -> { long s = System.nanoTime(); r.run(); t[0] = System.nanoTime() - s; });
            a.start(); a.join();
            best = Math.min(best, t[0] / (double) OPS);
        }
        return best;
    }

    static void await(CountDownLatch l) { try { l.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); } }

    static Runnable inc(VarHandle h, Object o) { return () -> { for (long i = 0; i < OPS; i++) h.getAndAdd(o, 1L); }; }

    static String fmt(double ns) { return String.format("%.2f", ns); }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version") + " cpus=" + Runtime.getRuntime().availableProcessors());
        out("layout.adjacent", "a@" + offset(Adjacent.class, "a") + " b@" + offset(Adjacent.class, "b"));
        out("layout.padded56", "a@" + offset(Padded56.class, "a") + " b@" + offset(Padded56.class, "b"));
        out("layout.padded120", "a@" + offset(Padded120.class, "a") + " b@" + offset(Padded120.class, "b"));
        out("layout.contended", "a@" + offset(Annotated.class, "a") + " b@" + offset(Annotated.class, "b"));

        if (args.length > 0) return;   // 只看布局

        Adjacent adj = new Adjacent(); Padded56 p56 = new Padded56(); Padded120 p120 = new Padded120(); Annotated ann = new Annotated();
        // 预热：让各个循环都被编译
        for (int i = 0; i < 2; i++) { solo(inc(AA, adj)); solo(inc(PA56, p56)); solo(inc(PA120, p120)); solo(inc(CA, ann)); }

        double one = solo(inc(AA, new Adjacent()));
        out("write.solo", fmt(one));
        double dAdj = race(inc(AA, adj), inc(AB, adj));
        double d56 = race(inc(PA56, p56), inc(PB56, p56));
        double d120 = race(inc(PA120, p120), inc(PB120, p120));
        double dAnn = race(inc(CA, ann), inc(CB, ann));
        out("write.adjacent", fmt(dAdj));
        out("write.padded56", fmt(d56));
        out("write.padded120", fmt(d120));
        out("write.contended", fmt(dAnn));
        out("ratio.adjacent_vs_solo", fmt(dAdj / one));
        out("ratio.adjacent_vs_contended", fmt(dAdj / dAnn));

        // 数组：相邻元素与相隔 32 个元素（256 字节）
        AtomicLongArray arr = new AtomicLongArray(64);
        double near = race(() -> { for (long i = 0; i < OPS; i++) arr.getAndIncrement(16); }, () -> { for (long i = 0; i < OPS; i++) arr.getAndIncrement(17); });
        double far = race(() -> { for (long i = 0; i < OPS; i++) arr.getAndIncrement(16); }, () -> { for (long i = 0; i < OPS; i++) arr.getAndIncrement(48); });
        out("array.neighbors", fmt(near));
        out("array.32_apart", fmt(far));

        // 扫描：16 个起始位置上，相距 8 个元素（64 字节）与 16 个元素（128 字节）的两个元素各有多少对互相拖慢
        AtomicLongArray scan = new AtomicLongArray(96);
        final long scanOps = OPS / 5;
        int slow64 = 0, slow128 = 0; StringBuilder which = new StringBuilder();
        for (int start = 16; start < 32; start++) {
            for (int dist : new int[]{8, 16}) {
                final int i1 = start, i2 = start + dist;
                double best = Double.MAX_VALUE;
                for (int round = 0; round < 2; round++) {
                    CountDownLatch go = new CountDownLatch(1); long[] t = new long[2];
                    Thread a = new Thread(() -> { await(go); long s0 = System.nanoTime(); for (long i = 0; i < scanOps; i++) scan.getAndIncrement(i1); t[0] = System.nanoTime() - s0; });
                    Thread b = new Thread(() -> { await(go); long s0 = System.nanoTime(); for (long i = 0; i < scanOps; i++) scan.getAndIncrement(i2); t[1] = System.nanoTime() - s0; });
                    a.start(); b.start(); go.countDown(); a.join(); b.join();
                    best = Math.min(best, Math.max(t[0], t[1]) / (double) scanOps);
                }
                boolean slow = best > 3 * far;
                if (dist == 8 && slow) { slow64++; which.append(start).append(' '); }
                if (dist == 16 && slow) slow128++;
            }
        }
        out("scan.64_bytes_apart", slow64 + "/16");
        out("scan.64_bytes_slow_starts", which.toString().trim());
        out("scan.128_bytes_apart", slow128 + "/16");

        // 一个线程只读，另一个线程写它旁边的字段
        Adjacent ro = new Adjacent(); Annotated roAnn = new Annotated();
        long[] sink = new long[2];
        Runnable readAdj = () -> { long s = 0; for (long i = 0; i < OPS; i++) s += (long) AB.getVolatile(ro); sink[0] = s; };
        Runnable readAnn = () -> { long s = 0; for (long i = 0; i < OPS; i++) s += (long) CB.getVolatile(roAnn); sink[1] = s; };
        double readAlone = solo(readAdj);
        double[] tr = readerTime(readAdj, inc(AA, ro));
        double[] tc = readerTime(readAnn, inc(CA, roAnn));
        out("read.alone", fmt(readAlone));
        out("read.next_to_writer", fmt(tr[0]));
        out("read.contended_writer", fmt(tc[0]));

        // 普通字段：循环里的写入可以被编译器合并，测到的不是内存写入
        Plain plain = new Plain();
        Runnable pa = () -> { for (long i = 0; i < OPS; i++) plain.a++; };
        Runnable pb = () -> { for (long i = 0; i < OPS; i++) plain.b++; };
        for (int i = 0; i < 3; i++) { solo(pa); solo(pb); }
        double dPlain = race(pa, pb);
        out("plain.adjacent", String.format("%.4f", dPlain));
        out("plain.values", "a=" + plain.a + " b=" + plain.b);
    }

    /** 只统计第一个线程（读者）的耗时；写者一直写到读者结束 */
    static double[] readerTime(Runnable reader, Runnable writerOnce) throws Exception {
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            CountDownLatch go = new CountDownLatch(1);
            long[] t = new long[1];
            Thread w = new Thread(() -> { await(go); writerOnce.run(); });
            Thread r = new Thread(() -> { await(go); long s = System.nanoTime(); reader.run(); t[0] = System.nanoTime() - s; });
            w.start(); r.start(); go.countDown(); r.join(); w.join();
            best = Math.min(best, t[0] / (double) OPS);
        }
        return new double[]{best};
    }
}
