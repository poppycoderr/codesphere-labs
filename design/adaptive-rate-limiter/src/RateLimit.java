import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import redis.clients.jedis.Jedis;

/**
 * 限流组件：可控时钟下三种算法的窗口边界、本地并发、多实例共享 Redis 的原子性与配额平分、规则热更新、存储不可用时的降级策略。
 * 运行：java -cp <jedis 及依赖> src/RateLimit.java <输出目录>，连接 127.0.0.1:6379。
 */
public class RateLimit {

    interface Limiter {
        boolean tryAcquire(long nowMillis);
    }

    /** 固定窗口：每个整秒一个计数器。 */
    static final class FixedWindow implements Limiter {
        final int limit;
        long window = -1;
        int count;

        FixedWindow(int limit) {
            this.limit = limit;
        }

        public boolean tryAcquire(long now) {
            long w = now / 1000;
            if (w != window) {
                window = w;
                count = 0;
            }
            if (count >= limit) return false;
            count++;
            return true;
        }
    }

    /** 滑动日志：保留最近 1 秒内放行的时刻。 */
    static final class SlidingLog implements Limiter {
        final int limit;
        final ArrayDeque<Long> log = new ArrayDeque<>();

        SlidingLog(int limit) {
            this.limit = limit;
        }

        public boolean tryAcquire(long now) {
            while (!log.isEmpty() && log.peekFirst() <= now - 1000) log.pollFirst();
            if (log.size() >= limit) return false;
            log.addLast(now);
            return true;
        }
    }

    /** 令牌桶：容量 capacity，每秒补充 rate 个。 */
    static final class TokenBucket implements Limiter {
        final double capacity;
        final double rate;
        double tokens;
        long last;

        TokenBucket(int capacity, int rate) {
            this.capacity = capacity;
            this.rate = rate;
            this.tokens = capacity;
        }

        public boolean tryAcquire(long now) {
            tokens = Math.min(capacity, tokens + (now - last) * rate / 1000.0);
            last = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
    }

    static void out(String key, Object value) {
        System.out.println(key + "\t" + value);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        algorithms();
        localConcurrency();
        distributed();
        hotReload();
        outage(dir);
    }

    /** 同一段流量：第 990ms 到达 100 个请求，第 1000ms 再到达 100 个；限额每秒 100。统计放行数与任意 1 秒窗口内的最大放行数。 */
    static void algorithms() {
        List<Long> arrivals = new ArrayList<>();
        for (int i = 0; i < 100; i++) arrivals.add(990L);
        for (int i = 0; i < 100; i++) arrivals.add(1000L);
        for (String name : List.of("fixed-window", "sliding-log", "token-bucket")) {
            Limiter l = switch (name) {
                case "fixed-window" -> new FixedWindow(100);
                case "sliding-log" -> new SlidingLog(100);
                default -> new TokenBucket(100, 100);
            };
            List<Long> accepted = new ArrayList<>();
            for (long t : arrivals) if (l.tryAcquire(t)) accepted.add(t);
            int maxInWindow = 0;
            for (long start : accepted) {
                int n = 0;
                for (long t : accepted) if (t >= start && t < start + 1000) n++;
                maxInWindow = Math.max(maxInWindow, n);
            }
            out("algorithm." + name, "第 990ms 与第 1000ms 各到达 100 个：放行 " + accepted.size() + "，任意 1 秒内最多放行 " + maxInWindow);
        }
    }

    /** 32 个线程各请求 200 次，限额 1000。先检查后递增的两步之间有一次 50µs 的停顿。 */
    static void localConcurrency() throws Exception {
        for (String mode : List.of("check-then-increment", "compare-and-set")) {
            AtomicLong count = new AtomicLong();
            AtomicInteger accepted = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(32);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(32);
            for (int t = 0; t < 32; t++) {
                pool.execute(() -> {
                    try {
                        go.await();
                        for (int i = 0; i < 200; i++) {
                            boolean ok;
                            if (mode.equals("check-then-increment")) {
                                ok = count.get() < 1000;
                                LockSupport.parkNanos(50_000);
                                if (ok) count.incrementAndGet();
                            } else {
                                long c;
                                do {
                                    c = count.get();
                                    ok = c < 1000;
                                } while (ok && !count.compareAndSet(c, c + 1));
                            }
                            if (ok) accepted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            done.await();
            pool.shutdown();
            out("local." + mode, "6400 个请求、限额 1000：放行 " + accepted.get());
        }
    }

    static final String LUA = """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            if n > tonumber(ARGV[1]) then return 0 end
            return 1""";

    /** 16 个实例共享 Redis，全局限额 1000，共 2000 个请求。GET 与 INCR 之间有一次 100µs 的停顿，代表应用在读与写之间的处理。 */
    static void distributed() throws Exception {
        for (String mode : List.of("get-then-incr", "lua")) {
            try (Jedis j = new Jedis("127.0.0.1", 6379)) {
                j.del("rl:" + mode);
            }
            AtomicInteger accepted = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch done = new CountDownLatch(16);
            for (int inst = 0; inst < 16; inst++) {
                pool.execute(() -> {
                    try (Jedis j = new Jedis("127.0.0.1", 6379)) {
                        for (int i = 0; i < 125; i++) {
                            boolean ok;
                            if (mode.equals("lua")) {
                                ok = ((Long) j.eval(LUA, List.of("rl:" + mode), List.of("1000", "60000"))) == 1L;
                            } else {
                                String v = j.get("rl:" + mode);
                                ok = v == null || Long.parseLong(v) < 1000;
                                LockSupport.parkNanos(100_000);
                                if (ok) j.incr("rl:" + mode);
                            }
                            if (ok) accepted.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
            pool.shutdown();
            out("distributed." + mode, "16 个实例、2000 个请求、全局限额 1000：放行 " + accepted.get());
        }
        int[] traffic = {1400, 200, 200, 200};
        int accepted = 0;
        for (int t : traffic) accepted += Math.min(t, 250);
        out("distributed.split_quota", "每个实例本地限额 250，流量按 70%/10%/10%/10% 分到 4 个实例：放行 " + accepted
                + " / 2000（全局容量 1000），热点实例拒绝 " + (traffic[0] - 250));
    }

    record Rule(int version, int limit) {
    }

    /** 规则存储：新规则通过校验才替换当前版本。 */
    static final class RuleStore {
        final AtomicReference<Rule> current = new AtomicReference<>(new Rule(1, 1000));
        final List<String> audit = new ArrayList<>();

        void publish(Rule r) {
            if (r.limit() <= 0) {
                audit.add("拒绝版本 " + r.version() + "（limit=" + r.limit() + "），保留版本 " + current.get().version());
                return;
            }
            Rule old = current.getAndSet(r);
            audit.add("版本 " + old.version() + " → " + r.version() + "（limit " + old.limit() + " → " + r.limit() + "）");
        }
    }

    static void hotReload() {
        RuleStore store = new RuleStore();
        int count = 0;
        int accepted = 0;
        for (int i = 0; i < 1500; i++) {
            if (i == 300) store.publish(new Rule(2, -5));
            if (i == 600) store.publish(new Rule(3, 800));
            if (count < store.current.get().limit()) {
                count++;
                accepted++;
            }
        }
        out("reload.audit", String.join("；", store.audit));
        out("reload.effect", "同一窗口 1500 个请求：放行 " + accepted + "（第 600 个请求时限额从 1000 降到 800，已计数的 600 个不回收）");
    }

    /** Redis 被暂停（由脚本执行 docker pause）期间，4 个实例各发 500 个请求，命令超时 50ms。 */
    static void outage(Path dir) throws Exception {
        Path ready = dir.resolve("outage.ready");
        Path paused = dir.resolve("outage.paused");
        Files.writeString(ready, "ready");
        for (int i = 0; i < 600 && !Files.exists(paused); i++) Thread.sleep(100);
        for (String policy : List.of("fail-open", "fail-closed", "local-fallback")) {
            AtomicInteger accepted = new AtomicInteger();
            List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch done = new CountDownLatch(4);
            for (int inst = 0; inst < 4; inst++) {
                pool.execute(() -> {
                    FixedWindow local = new FixedWindow(250);
                    boolean breakerOpen = false;
                    Jedis j = null;
                    try {
                        for (int i = 0; i < 500; i++) {
                            long t0 = System.nanoTime();
                            boolean ok;
                            try {
                                if (breakerOpen) throw new IllegalStateException("熔断");
                                if (j == null) j = new Jedis("127.0.0.1", 6379, 50);
                                ok = ((Long) j.eval(LUA, List.of("rl:outage"), List.of("1000", "60000"))) == 1L;
                            } catch (RuntimeException e) {
                                breakerOpen = true;
                                ok = switch (policy) {
                                    case "fail-open" -> true;
                                    case "fail-closed" -> false;
                                    default -> local.tryAcquire(0);
                                };
                            }
                            latencies.add((System.nanoTime() - t0) / 1000);
                            if (ok) accepted.incrementAndGet();
                        }
                    } finally {
                        try {
                            if (j != null) j.close();
                        } catch (RuntimeException ignored) {
                            // 连接已损坏，关闭失败不影响统计
                        }
                        done.countDown();
                    }
                });
            }
            done.await();
            pool.shutdown();
            latencies.sort(null);
            out("outage." + policy, "Redis 暂停期间 2000 个请求：放行 " + accepted.get() + "，判定耗时最大 " + latencies.get(latencies.size() - 1) / 1000 + "ms");
        }
    }
}
