import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 一个值该作为参数传进来、放进对象的字段，还是放进线程上下文：放错位置的三种后果。
 * 交错顺序都由闩锁固定，输出确定。
 */
public class ParamLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    // 一、每次请求不同的数据，放进了共享对象的字段
    static final class GreetingServiceWithField {
        private String currentUser;                                       // 「减少参数」把它挪成了字段
        void setUser(String u) { currentUser = u; }
        String greet(CountDownLatch pause, CountDownLatch resume) throws InterruptedException {
            pause.countDown(); resume.await();                            // 模拟中间的 I/O
            return "hello " + currentUser;
        }
    }
    static final class GreetingService { String greet(String user) { return "hello " + user; } }

    // 二、同类型的参数调换了顺序
    record Event(String name, Instant at) { }
    static final List<Event> EVENTS = List.of(new Event("a", Instant.parse("2026-10-01T00:00:00Z")), new Event("b", Instant.parse("2026-10-05T00:00:00Z")));
    static long count(Instant from, Instant to) { return EVENTS.stream().filter(e -> !e.at().isBefore(from) && e.at().isBefore(to)).count(); }
    record Period(Instant from, Instant to) {
        Period { if (!from.isBefore(to)) throw new IllegalArgumentException("from 必须早于 to：" + from + " / " + to); }
        boolean contains(Instant t) { return !t.isBefore(from) && t.isBefore(to); }
    }
    static long count(Period p) { return EVENTS.stream().filter(e -> p.contains(e.at())).count(); }

    // 三、线程上下文用完没有清理
    static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));

        GreetingServiceWithField shared = new GreetingServiceWithField();
        CountDownLatch alicePaused = new CountDownLatch(1), aliceResume = new CountDownLatch(1);
        String[] aliceResult = new String[1];
        Thread alice = new Thread(() -> { shared.setUser("alice"); try { aliceResult[0] = shared.greet(alicePaused, aliceResume); } catch (InterruptedException ignored) { } });
        alice.start(); alicePaused.await();
        shared.setUser("bob");                                            // 另一个请求在 alice 等 I/O 时进来
        aliceResume.countDown(); alice.join();
        out("field.shared", "单例服务把当前用户放在字段里，alice 的请求等 I/O 时 bob 的请求进来：alice 收到「" + aliceResult[0] + "」");
        out("field.param", "作为参数传入：alice 收到「" + new GreetingService().greet("alice") + "」");

        Instant oct1 = Instant.parse("2026-10-01T00:00:00Z"), oct10 = Instant.parse("2026-10-10T00:00:00Z");
        out("swap.raw", "count(from, to) 正确调用 " + count(oct1, oct10) + " 条；参数写反成 count(oct10, oct1)：编译通过，返回 " + count(oct10, oct1) + " 条，没有任何错误");
        out("swap.object", "换成参数对象 Period(from, to)，写反时：" + attempt(() -> count(new Period(oct10, oct1))));

        ExecutorService pool = Executors.newFixedThreadPool(1);
        pool.submit(() -> TENANT.set("tenant-a")).get();                  // 第一个任务设置了租户，没有清理
        String leaked = pool.submit(TENANT::get).get();                   // 下一个任务没有设置，直接读
        out("context.leak", "线程池里的上一个任务设置了租户、没有清理；同一个线程上的下一个任务读到：" + leaked);
        pool.submit(() -> { TENANT.set("tenant-a"); try { return null; } finally { TENANT.remove(); } }).get();
        out("context.cleaned", "改成 try/finally 里 remove：下一个任务读到 " + pool.submit(TENANT::get).get());
        pool.shutdown();
    }

    interface Call { Object run() throws Exception; }
    static String attempt(Call c) { try { return String.valueOf(c.run()); } catch (Exception e) { return "构造时抛出 " + e.getClass().getSimpleName() + "（" + e.getMessage() + "）"; } }
}
