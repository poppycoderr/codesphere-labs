import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * 加了 synchronized，保护住了吗：取决于各个线程锁的是不是同一个对象。
 * 计数场景：两个线程各做 20 万次「读、加一、写」，看有没有丢更新（最多重复 5 轮，任何一轮丢了就算丢）。
 * 判断两把锁是不是同一把：一个线程拿着锁 A 不放，看另一个线程 300 毫秒内能不能拿到锁 B。
 */
public class LockLab {
    static final int N = 200_000;
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    interface Body { void run(int thread) throws Exception; }
    interface IntRead { int get(); }

    static void twoThreads(Body body) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            final int id = t;
            ts[t] = new Thread(() -> { try { start.await(); for (int i = 0; i < N; i++) body.run(id); } catch (Exception e) { throw new IllegalStateException(e); } });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
    }

    static String lost(Runnable reset, Body body, IntRead read) throws Exception {
        for (int round = 0; round < 5; round++) {
            reset.run(); twoThreads(body);
            if (read.get() < 2 * N) return "丢了更新（结果小于 400000）";
        }
        return "5 轮都是 400000";
    }

    /** 线程 A 持有 lockA 期间，线程 B 能不能在 300 毫秒内拿到 lockB：能，说明是两把互不相干的锁 */
    static boolean independent(Object lockA, Object lockB) throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), entered = new CountDownLatch(1);
        Thread a = new Thread(() -> { synchronized (lockA) { held.countDown(); try { release.await(); } catch (InterruptedException ignored) { } } });
        a.start(); held.await();
        Thread b = new Thread(() -> { synchronized (lockB) { entered.countDown(); } });
        b.start();
        boolean got = entered.await(300, TimeUnit.MILLISECONDS);
        release.countDown(); a.join(); b.join();
        return got;
    }
    static String verdict(boolean independent) { return independent ? "另一个线程照样进入（两把锁）" : "另一个线程被挡住（同一把锁）"; }

    static int counter;
    static final class Service {
        static int total;
        synchronized void add() { total++; }
        static synchronized void addStatic() { total++; }
    }
    static final class Mixed {
        int n; final ReentrantLock lock = new ReentrantLock();
        synchronized void viaSynchronized() { n++; }
        void viaLock() { lock.lock(); try { n++; } finally { lock.unlock(); } }
    }
    static Integer boxed = 0;

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、对照
        out("baseline.no_lock", "不加锁：" + lost(() -> counter = 0, t -> counter++, () -> counter));
        Object shared = new Object();
        out("baseline.same_lock", "两个线程锁同一个对象：" + lost(() -> counter = 0, t -> { synchronized (shared) { counter++; } }, () -> counter));

        // 二、实例方法上的 synchronized 锁的是 this：两个实例是两把锁
        Service[] services = {new Service(), new Service()};
        out("instance.count", "两个实例各自的 synchronized 方法修改同一个静态变量：" + lost(() -> Service.total = 0, t -> services[t].add(), () -> Service.total));
        out("instance.independent", "一个线程在实例 1 的同步方法里：" + verdict(independent(services[0], services[1])));
        out("instance.static_fix", "改成 static synchronized（锁的是类对象）：" + lost(() -> Service.total = 0, t -> Service.addStatic(), () -> Service.total));

        // 三、同一个字段，一处用 synchronized，一处用 ReentrantLock
        Mixed mixed = new Mixed();
        out("mixed_locks", "一个方法用 synchronized、另一个方法用 ReentrantLock 保护同一个字段：" + lost(() -> mixed.n = 0, t -> { if (t == 0) mixed.viaSynchronized(); else mixed.viaLock(); }, () -> mixed.n));

        // 四、锁对象是每次新建的，或者会被重新赋值
        out("new_each_time", "同步块锁的是方法里刚 new 出来的对象：" + lost(() -> counter = 0, t -> { Object lock = new Object(); synchronized (lock) { counter++; } }, () -> counter));
        out("boxed.count", "synchronized (boxed) { boxed++; }：" + lost(() -> boxed = 0, t -> { synchronized (boxed) { boxed++; } }, () -> boxed));
        Integer before = 1000, after = before + 1;
        out("boxed.identity", "Integer 自增之后，变量指向的还是原来那个对象 = " + (before == after));
        out("boxed.javac", "javac 对这种写法的警告：" + compileWarning("class T { Integer n = 0; void f() { synchronized (n) { n++; } } }"));

        // 五、拿业务键当锁：内容相同不等于同一个对象
        String k1 = "order-" + Integer.parseInt("42"), k2 = "order-" + Integer.parseInt("42");
        out("key.string_runtime", "两个运行时拼出来的 \"order-42\"（equals 为 " + k1.equals(k2) + "，== 为 " + (k1 == k2) + "）：" + verdict(independent(k1, k2)));
        out("key.string_interned", "对它们各自调用 intern() 之后：" + verdict(independent(k1.intern(), k2.intern())));
        out("key.string_literal", "两个互不相干的类各自用字面量 \"LOCK\" 当锁：" + verdict(independent(ModuleA.LOCK, ModuleB.LOCK)));
        out("key.integer_100", "Integer.valueOf(100) 取两次：" + verdict(independent(Integer.valueOf(100), Integer.valueOf(100))));
        out("key.integer_1000", "Integer.valueOf(1000) 取两次：" + verdict(independent(Integer.valueOf(1000), Integer.valueOf(1000))));
        out("key.long_id", "Long.valueOf(20261010L) 取两次（常见的「按用户 ID 加锁」）：" + verdict(independent(Long.valueOf(20261010L), Long.valueOf(20261010L))));
        Map<String, Object> locks = new ConcurrentHashMap<>();
        out("key.lock_table", "按键从 ConcurrentHashMap 里 computeIfAbsent 取锁对象：" + verdict(independent(locks.computeIfAbsent(k1, x -> new Object()), locks.computeIfAbsent(k2, x -> new Object()))));
        out("key.lock_table_other", "同一张表里另一个键 \"order-43\"：" + verdict(independent(locks.computeIfAbsent(k1, x -> new Object()), locks.computeIfAbsent("order-43", x -> new Object()))));

        // 六、写的一方加锁，读的一方不加：强制让读发生在两次写之间
        out("reader.unlocked", "写方在锁内先改 a 再改 b，读方不加锁，在两次修改之间读到 a + b = " + readBetweenWrites(false) + "（约定恒为 0）");
        out("reader.locked", "读方也加同一把锁：读到 a + b = " + readBetweenWrites(true));

        // 七、每个操作都同步，组合起来不同步：强制两个线程都先检查、再添加
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            ts[t] = new Thread(() -> { try { if (list.isEmpty()) { bothChecked.await(); list.add("init"); } } catch (Exception e) { throw new IllegalStateException(e); } });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        out("compound.check_then_act", "synchronizedList 上两个线程各自「为空才添加」，都先检查完再添加：列表里有 " + list.size() + " 个元素");
    }

    static final class ModuleA { static final String LOCK = "LOCK"; }
    static final class ModuleB { static final String LOCK = "LOCK"; }

    static int readBetweenWrites(boolean readerLocks) throws Exception {
        int[] pair = new int[2]; int[] seen = {Integer.MIN_VALUE};
        Object lock = new Object();
        CountDownLatch firstWritten = new CountDownLatch(1), readDone = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            synchronized (lock) {
                pair[0]++;
                firstWritten.countDown();
                try { readDone.await(300, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
                pair[1]--;
            }
        });
        Thread reader = new Thread(() -> {
            try { firstWritten.await(); } catch (InterruptedException ignored) { }
            if (readerLocks) synchronized (lock) { seen[0] = pair[0] + pair[1]; } else seen[0] = pair[0] + pair[1];
            readDone.countDown();
        });
        writer.start(); reader.start(); writer.join(); reader.join();
        return seen[0];
    }

    static String compileWarning(String source) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///T.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        ToolProvider.getSystemJavaCompiler().getTask(null, null, diagnostics, List.of("-proc:none", "-d", System.getProperty("java.io.tmpdir")), null, List.of(file)).call();
        return diagnostics.getDiagnostics().stream().map(d -> d.getKind() + " " + d.getMessage(java.util.Locale.ROOT)).findFirst().orElse("没有警告");
    }
}
