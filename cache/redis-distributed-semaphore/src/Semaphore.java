import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式信号量实验：List 存许可、ZSET 记录持有者与租约，释放和补偿用 Lua。
 * 用法：java src/Semaphore.java <输出目录>，连接 127.0.0.1:6379。结果写成 key\tvalue 行，由 summarize.py 断言。
 */
public class Semaphore {

    static final String RELEASE = """
            if redis.call('ZREM', KEYS[2], ARGV[1]) == 1 then
              redis.call('RPUSH', KEYS[1], 'p')
              return 1
            end
            return 0""";

    static final String COMPENSATE = """
            local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1])
            if #expired > 0 then
              redis.call('ZREM', KEYS[2], unpack(expired))
            end
            local capacity  = tonumber(ARGV[2])
            local available = redis.call('LLEN', KEYS[1])
            local held      = redis.call('ZCARD', KEYS[2])
            local missing   = capacity - available - held
            if missing > 0 then
              for _ = 1, missing do redis.call('RPUSH', KEYS[1], 'p') end
            end
            return missing""";

    /** 最小的 RESP2 客户端：一条连接，一次一条命令，不做线程共享。 */
    static final class Resp implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Resp() throws IOException {
            socket = new Socket("127.0.0.1", 6379);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        Object call(Object... args) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
            for (Object a : args) {
                byte[] b = String.valueOf(a).getBytes(StandardCharsets.UTF_8);
                sb.append('$').append(b.length).append("\r\n").append(String.valueOf(a)).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return read();
        }

        long num(Object... args) throws IOException {
            return (Long) call(args);
        }

        private Object read() throws IOException {
            int type = in.read();
            if (type < 0) throw new IOException("连接被关闭");
            String line = line();
            return switch (type) {
                case '+' -> line;
                case '-' -> throw new IOException("Redis 错误：" + line);
                case ':' -> Long.parseLong(line);
                case '$' -> {
                    int n = Integer.parseInt(line);
                    if (n < 0) yield null;
                    byte[] b = in.readNBytes(n + 2);
                    yield new String(b, 0, n, StandardCharsets.UTF_8);
                }
                case '*' -> {
                    int n = Integer.parseInt(line);
                    if (n < 0) yield null;
                    List<Object> items = new ArrayList<>();
                    for (int i = 0; i < n; i++) items.add(read());
                    yield items;
                }
                default -> throw new IOException("未知回复类型 " + (char) type);
            };
        }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != '\r') {
                if (c < 0) throw new IOException("连接被关闭");
                sb.append((char) c);
            }
            in.read();
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** 一个信号量实例的操作，对应文章第四节的 acquire / release / compensate。 */
    record Sem(
            String permits,
            String holders,
            int capacity
    ) {
        static Sem named(String name, int capacity) {
            return new Sem("sem:{" + name + "}:permits", "sem:{" + name + "}:holders", capacity);
        }

        void init(Resp r) throws IOException {
            r.call("DEL", permits, holders);
            for (int i = 0; i < capacity; i++) r.call("RPUSH", permits, "p");
        }

        /** 返回持有者标识；等待超时返回 null。BRPOP 与 ZADD 之间崩溃会丢许可，由 compensate 补回。 */
        String acquire(Resp r, String instance, double waitSeconds, long leaseMillis) throws IOException {
            Object got = r.call("BRPOP", permits, waitSeconds);
            if (got == null) return null;
            String holder = instance + ":" + System.nanoTime();
            r.call("ZADD", holders, System.currentTimeMillis() + leaseMillis, holder);
            return holder;
        }

        long release(Resp r, String holder) throws IOException {
            return r.num("EVAL", RELEASE, 2, permits, holders, holder);
        }

        long compensate(Resp r) throws IOException {
            return r.num("EVAL", COMPENSATE, 2, permits, holders, System.currentTimeMillis(), capacity);
        }

        String state(Resp r) throws IOException {
            return "available=" + r.num("LLEN", permits) + " holders=" + r.num("ZCARD", holders);
        }
    }

    static PrintStream result;

    static void put(String key, Object value) {
        result.println(key + "\t" + value);
        System.out.println(key + "\t" + value);
    }

    public static void main(String[] args) throws Exception {
        result = new PrintStream(Files.newOutputStream(Path.of(args[0], "results.tsv")), true, StandardCharsets.UTF_8);
        try (Resp r = new Resp()) {
            put("redis_version", ((String) r.call("INFO", "server")).lines()
                    .filter(l -> l.startsWith("redis_version:")).findFirst().orElseThrow().substring(14));
            basic(r);
            lostBetweenPopAndAdd(r);
        }
        stress();
        leaseExpiredWhileRunning();
    }

    /** 场景一至三：获取、等待超时、释放与重复释放、租约过期后的补偿。 */
    static void basic(Resp r) throws Exception {
        Sem sem = Sem.named("cache-loader", 3);
        sem.init(r);
        List<String> held = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            held.add(sem.acquire(r, "pod-" + i, 2, 600_000));
            put("acquire.pod-" + i, sem.state(r));
        }
        long t0 = System.nanoTime();
        String fourth = sem.acquire(r, "pod-4", 2, 600_000);
        put("acquire.pod-4.result", fourth == null ? "timeout" : fourth);
        put("acquire.pod-4.waited_ms", (System.nanoTime() - t0) / 1_000_000);

        put("release.pod-1.first", sem.release(r, held.get(0)) + " " + sem.state(r));
        put("release.pod-1.second", sem.release(r, held.get(0)) + " " + sem.state(r));

        r.call("ZADD", sem.holders(), "XX", System.currentTimeMillis() - 1, held.get(1));
        put("compensate.after_pod-2_lease_expired", sem.compensate(r) + " " + sem.state(r));
        put("release.pod-2.after_reclaimed", sem.release(r, held.get(1)) + " " + sem.state(r));
        put("release.pod-3", sem.release(r, held.get(2)) + " " + sem.state(r));
        put("compensate.when_balanced", sem.compensate(r) + " " + sem.state(r));
    }

    /** 场景四：BRPOP 取走许可后、ZADD 之前进程崩溃，许可既不在 List 也不在 ZSET。 */
    static void lostBetweenPopAndAdd(Resp r) throws Exception {
        Sem sem = Sem.named("pop-crash", 3);
        sem.init(r);
        r.call("BRPOP", sem.permits(), 1);
        put("pop_crash.before_compensate", sem.state(r));
        put("pop_crash.compensate", sem.compensate(r) + " " + sem.state(r));
    }

    /** 场景五：20 个线程各获取、释放 50 次，记录同时持有许可的最大数量。 */
    static void stress() throws Exception {
        Sem sem = Sem.named("stress", 3);
        try (Resp r = new Resp()) {
            sem.init(r);
        }
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger timeouts = new AtomicInteger();
        AtomicInteger doubleRelease = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(20);
        long t0 = System.nanoTime();
        for (int t = 0; t < 20; t++) {
            int id = t;
            Thread.ofPlatform().start(() -> {
                try (Resp r = new Resp()) {
                    for (int i = 0; i < 50; i++) {
                        String h = sem.acquire(r, "worker-" + id, 5, 60_000);
                        if (h == null) {
                            timeouts.incrementAndGet();
                            continue;
                        }
                        acquired.incrementAndGet();
                        int now = inside.incrementAndGet();
                        maxInside.accumulateAndGet(now, Math::max);
                        Thread.sleep(2 + (id * 7 + i) % 4);
                        inside.decrementAndGet();
                        sem.release(r, h);
                        doubleRelease.addAndGet((int) sem.release(r, h));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        put("stress.threads_x_rounds", "20x50");
        put("stress.acquired", acquired.get());
        put("stress.timeouts", timeouts.get());
        put("stress.max_concurrent_holders", maxInside.get());
        put("stress.double_release_succeeded", doubleRelease.get());
        put("stress.elapsed_ms", (System.nanoTime() - t0) / 1_000_000);
        try (Resp r = new Resp()) {
            put("stress.final", sem.state(r));
        }
    }

    /** 场景六：容量 1，持有者的租约 500ms，但任务要跑 1500ms；补偿在 700ms 回收许可后，另一实例进入，出现 2 个并发。 */
    static void leaseExpiredWhileRunning() throws Exception {
        Sem sem = Sem.named("lease", 1);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        long t0 = System.nanoTime();
        List<String> timeline = new ArrayList<>();
        java.util.function.Consumer<String> ev = s -> {
            synchronized (timeline) {
                timeline.add((System.nanoTime() - t0) / 1_000_000 + "ms " + s);
            }
        };
        try (Resp r = new Resp()) {
            sem.init(r);
        }
        Thread a = Thread.ofPlatform().start(() -> {
            try (Resp r = new Resp()) {
                String h = sem.acquire(r, "pod-a", 1, 500);
                ev.accept("pod-a acquired lease=500ms");
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                Thread.sleep(1500);
                inside.decrementAndGet();
                ev.accept("pod-a finished, release=" + sem.release(r, h));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(700);
        try (Resp r = new Resp()) {
            ev.accept("compensate reclaimed=" + sem.compensate(r));
            String h = sem.acquire(r, "pod-b", 1, 60_000);
            ev.accept("pod-b acquired=" + (h != null) + ", pod-a still running=" + (inside.get() == 1));
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            Thread.sleep(300);
            inside.decrementAndGet();
            a.join();
            ev.accept("pod-b release=" + sem.release(r, h) + " " + sem.state(r));
        }
        for (String s : timeline) put("lease.timeline", s);
        put("lease.max_concurrent_holders", maxInside.get());
    }
}
