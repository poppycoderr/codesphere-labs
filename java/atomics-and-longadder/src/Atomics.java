import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.lang.invoke.*;

/**
 * volatile、CAS 与 LongAdder 各自解决什么问题，输出为「键<TAB>事实」：
 * 1. volatile 上的 i++ 丢更新；2. CAS 重试让计算函数执行多次；3. 节点复用下的 ABA；
 * 4. 不同线程数下的计数吞吐；5. 用 LongAdder.sum() 做限额会超发；6. VarHandle 的 CAS。
 */
public class Atomics {
    static final int THREADS = 8;

    public static void main(String[] args) throws Exception {
        volatileIncrement();
        casRetries();
        aba();
        throughput();
        limitCheck();
        varHandle();
    }

    // ---------- 1 ----------
    static volatile long volatileCount;

    static void volatileIncrement() throws Exception {
        for (int round = 1; round <= 3; round++) {
            volatileCount = 0;
            AtomicLong atomic = new AtomicLong();
            run(THREADS, i -> { for (int k = 0; k < 1_000_000; k++) { volatileCount++; atomic.incrementAndGet(); } });
            System.out.printf("volatile.%d\t%d 个线程各加 100 万次：volatile long 为 %,d（丢失 %,d），AtomicLong 为 %,d%n",
                    round, THREADS, volatileCount, THREADS * 1_000_000L - volatileCount, atomic.get());
        }
    }

    // ---------- 2 ----------
    record Account(long balance, long version) {
        Account apply(long delta) { return new Account(balance + delta, version + 1); }
    }

    static void casRetries() throws Exception {
        AtomicReference<Account> state = new AtomicReference<>(new Account(0, 0));
        LongAdder applyCalls = new LongAdder(), sideEffects = new LongAdder();
        run(THREADS, t -> {
            for (int k = 0; k < 100_000; k++) {
                Account before, after;
                do {
                    before = state.get();
                    applyCalls.increment();
                    sideEffects.increment(); // 假设这里「顺手」发了一条消息
                    after = before.apply(1);
                } while (!state.compareAndSet(before, after));
            }
        });
        long updates = THREADS * 100_000L;
        System.out.printf("cas\t%d 个线程各做 10 万次 CAS 更新：成功 %,d 次，计算函数执行 %,d 次（%.2f 倍），放在循环里的副作用执行 %,d 次，最终余额 %,d%n",
                THREADS, updates, applyCalls.sum(), applyCalls.sum() / (double) updates, sideEffects.sum(), state.get().balance());
    }

    // ---------- 3 ----------
    static final class Node {
        final String value; Node next;
        Node(String value) { this.value = value; }
    }

    static String dump(Node n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; n != null && i < 10; n = n.next, i++) out.add(n.value);
        return out.toString();
    }

    static void aba() throws Exception {
        // 普通引用：线程 1 读到 A → B 后暂停；线程 2 弹出 A、弹出 B、把 A 压回；线程 1 的 CAS(A → B) 成功
        Node a = new Node("A"), b = new Node("B"), c = new Node("C");
        a.next = b; b.next = c;
        AtomicReference<Node> head = new AtomicReference<>(a);
        Node seen = head.get(), seenNext = seen.next;
        head.compareAndSet(a, b); head.compareAndSet(b, c); // 线程 2 弹出 A、B（B 已交给别人使用）
        a.next = c; head.compareAndSet(c, a);                 // 线程 2 复用节点 A 压回
        boolean ok = head.compareAndSet(seen, seenNext);      // 线程 1 继续：以为头还是 A，把头设为 B
        System.out.printf("aba.plain\tAtomicReference：线程 1 的 CAS %s，栈变成 %s（已被弹出的 B 回到了栈顶）%n", ok ? "成功" : "失败", dump(head.get()));

        Node a2 = new Node("A"), b2 = new Node("B"), c2 = new Node("C");
        a2.next = b2; b2.next = c2;
        AtomicStampedReference<Node> sh = new AtomicStampedReference<>(a2, 0);
        int[] stamp = new int[1];
        Node seen2 = sh.get(stamp); int seenStamp = stamp[0]; Node seenNext2 = seen2.next;
        sh.compareAndSet(a2, b2, 0, 1); sh.compareAndSet(b2, c2, 1, 2);
        a2.next = c2; sh.compareAndSet(c2, a2, 2, 3);
        boolean ok2 = sh.compareAndSet(seen2, seenNext2, seenStamp, seenStamp + 1);
        System.out.printf("aba.stamped\tAtomicStampedReference：线程 1 的 CAS %s（版本 %d → 当前 %d），栈仍是 %s%n", ok2 ? "成功" : "失败", seenStamp, sh.getStamp(), dump(sh.getReference()));
    }

    // ---------- 4 ----------
    interface Counter { void inc(); long get(); }

    static void throughput() throws Exception {
        Map<String, java.util.function.Supplier<Counter>> kinds = new LinkedHashMap<>();
        kinds.put("synchronized", () -> new Counter() { long v; public synchronized void inc() { v++; } public synchronized long get() { return v; } });
        kinds.put("AtomicLong", () -> new Counter() { final AtomicLong v = new AtomicLong(); public void inc() { v.incrementAndGet(); } public long get() { return v.get(); } });
        kinds.put("LongAdder", () -> new Counter() { final LongAdder v = new LongAdder(); public void inc() { v.increment(); } public long get() { return v.sum(); } });
        for (int threads : new int[] {1, 2, 4, 8}) {
            StringBuilder line = new StringBuilder();
            for (var e : kinds.entrySet()) {
                long best = 0;
                for (int round = 0; round < 3; round++) {
                    Counter c = e.getValue().get();
                    AtomicBoolean stop = new AtomicBoolean();
                    Thread timer = new Thread(() -> { sleep(500); stop.set(true); });
                    timer.start();
                    run(threads, i -> { while (!stop.get()) for (int k = 0; k < 1000; k++) c.inc(); });
                    timer.join();
                    best = Math.max(best, c.get() * 2); // 500ms → 每秒
                }
                line.append(String.format("%s %,d 万次/秒；", e.getKey(), best / 10_000));
            }
            System.out.printf("throughput.%d\t%d 个线程（3 轮取最好）：%s%n", threads, threads, line);
        }
    }

    // ---------- 5 ----------
    static void limitCheck() throws Exception {
        for (int round = 1; round <= 3; round++) {
            LongAdder adder = new LongAdder();
            AtomicLong atomic = new AtomicLong();
            CountDownLatch go = new CountDownLatch(1);
            run(THREADS, i -> {
                go.await();
                for (int k = 0; k < 10_000; k++) {
                    if (adder.sum() < 1000) adder.increment();
                    atomic.getAndUpdate(v -> v < 1000 ? v + 1 : v);
                }
            }, go);
            System.out.printf("limit.%d\t限额 1000，%d 个线程争抢：LongAdder 先 sum() 再 increment() 最终 %,d，AtomicLong 条件 CAS 最终 %,d%n", round, THREADS, adder.sum(), atomic.get());
        }
    }

    // ---------- 6 ----------
    static final class Slot { volatile int state; }
    static final VarHandle STATE;
    static { try { STATE = MethodHandles.lookup().findVarHandle(Slot.class, "state", int.class); } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); } }

    static void varHandle() throws Exception {
        Slot slot = new Slot();
        AtomicInteger winners = new AtomicInteger();
        run(THREADS, i -> { if (STATE.compareAndSet(slot, 0, 1)) winners.incrementAndGet(); });
        System.out.printf("varhandle\t%d 个线程对同一字段 VarHandle.compareAndSet(0 → 1)：成功 %d 个，字段值 %d%n", THREADS, winners.get(), slot.state);
    }

    // ---------- 工具 ----------
    interface Task { void run(int i) throws Exception; }

    static void run(int n, Task t) throws Exception { run(n, t, null); }

    static void run(int n, Task t, CountDownLatch go) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) { int id = i; fs.add(pool.submit(() -> { t.run(id); return null; })); }
        if (go != null) go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
