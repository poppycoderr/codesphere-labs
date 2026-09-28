import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis ZSET 延时队列：触发延迟来自轮询间隔还是批量上限、两步取删在多消费者下的重复消费、取出后崩溃的任务丢失与租约回收。
 * 输出为「键<TAB>事实」。运行：java src/DelayQueue.java，连接 127.0.0.1:6379。
 */
public class DelayQueue {

    static final class RedisError extends IOException {
        RedisError(String message) {
            super(message);
        }
    }

    /** 最小的 RESP2 客户端：一条连接，一次一条命令。 */
    static final class Resp implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Resp(String host, int port, int timeoutMillis) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        Object call(Object... args) throws IOException {
            send(args);
            return read();
        }

        void send(Object... args) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
            for (Object a : args) {
                String s = String.valueOf(a);
                sb.append('$').append(s.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(s).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        Object read() throws IOException {
            int type = in.read();
            if (type < 0) throw new IOException("连接被关闭");
            String line = line();
            return switch (type) {
                case '+' -> line;
                case '-' -> throw new RedisError(line);
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

        void noTimeout() throws IOException {
            socket.setSoTimeout(0);
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
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败不影响实验
            }
        }
    }

    /** 取出到期任务并在同一个脚本里删除。 */
    static final String POP = """
            local ready = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            if #ready > 0 then redis.call('ZREM', KEYS[1], unpack(ready)) end
            return ready""";

    /** 取出到期任务，移到处理中集合，score 为租约到期时间；处理完再从处理中集合删除。 */
    static final String CLAIM = """
            local ready = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            for _, m in ipairs(ready) do
              redis.call('ZREM', KEYS[1], m)
              redis.call('ZADD', KEYS[2], tonumber(ARGV[3]), m)
            end
            return ready""";

    /** 把租约已过期的任务放回待执行队列，立即到期。 */
    static final String REAP = """
            local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1])
            for _, m in ipairs(expired) do
              redis.call('ZREM', KEYS[2], m)
              redis.call('ZADD', KEYS[1], tonumber(ARGV[1]), m)
            end
            return #expired""";

    static Resp connect() throws IOException {
        return new Resp("127.0.0.1", 6379, 5000);
    }

    public static void main(String[] args) throws Exception {
        precision("spread.poll20", 1000, 500, 2500, 20, 1000);
        precision("spread.poll100", 1000, 500, 2500, 100, 1000);
        precision("spread.poll500", 1000, 500, 2500, 500, 1000);
        precision("spread.poll100.batch100", 1000, 500, 2500, 100, 100);
        precision("spread.poll500.batch100", 1000, 500, 2500, 500, 100);
        precision("burst.batch100", 1000, 1000, 1000, 100, 100);
        precision("burst.batch1000", 1000, 1000, 1000, 100, 1000);
        duplicates("twostep", 4);
        duplicates("twostep.checkzrem", 4);
        duplicates("lua", 4);
        crash(false);
        crash(true);
    }

    // ---------- 1. 触发延迟 ----------

    /** n 个任务的到期时间均匀分布在 [from, to] 毫秒之后；一个消费者每 pollMs 轮询一次，每次最多取 batch 个。 */
    static void precision(String key, int n, int from, int to, int pollMs, int batch) throws Exception {
        String q = "delay:" + key;
        Random r = new Random(7);
        Map<String, Long> due = new ConcurrentHashMap<>();
        try (Resp c = connect()) {
            c.call("DEL", q);
            long now = System.currentTimeMillis();
            for (int i = 0; i < n; i++) {
                long at = now + from + (to == from ? 0 : r.nextInt(to - from));
                due.put("t" + i, at);
                c.call("ZADD", q, at, "t" + i);
            }
            long[] delay = new long[n];
            int got = 0;
            int polls = 0;
            while (got < n) {
                Thread.sleep(pollMs);
                polls++;
                long t = System.currentTimeMillis();
                @SuppressWarnings("unchecked")
                List<Object> ready = (List<Object>) c.call("EVAL", POP, 1, q, t, batch);
                for (Object m : ready) {
                    delay[got++] = t - due.get((String) m);
                }
            }
            Arrays.sort(delay);
            out(key, "%d 个任务，轮询间隔 %d ms、每次最多 %d 个：轮询 %d 次，延迟 p50 %d ms，p90 %d ms，最大 %d ms，最小 %d ms".formatted(
                    n, pollMs, batch, polls, delay[n / 2], delay[n * 9 / 10], delay[n - 1], delay[0]));
        }
    }

    // ---------- 2. 多消费者重复消费 ----------

    /** 1000 个任务都已到期，consumers 个消费者同时抢；统计被处理超过一次的任务。 */
    static void duplicates(String mode, int consumers) throws Exception {
        String q = "delay:dup:" + mode;
        int n = 1000;
        try (Resp c = connect()) {
            c.call("DEL", q);
            for (int i = 0; i < n; i++) {
                c.call("ZADD", q, 0, "t" + i);
            }
        }
        Map<String, AtomicInteger> handled = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int k = 0; k < consumers; k++) {
            ts.add(Thread.ofPlatform().start(() -> {
                try (Resp c = connect()) {
                    start.await();
                    while (true) {
                        List<String> mine = new ArrayList<>();
                        if (mode.equals("lua")) {
                            for (Object m : (List<?>) c.call("EVAL", POP, 1, q, System.currentTimeMillis(), 10)) {
                                mine.add((String) m);
                            }
                        } else {
                            List<?> ready = (List<?>) c.call("ZRANGEBYSCORE", q, "-inf", System.currentTimeMillis(), "LIMIT", 0, 10);
                            for (Object m : ready) {
                                long removed = (Long) c.call("ZREM", q, m);
                                if (removed == 1 || mode.equals("twostep")) {   // checkzrem：只处理自己删掉的
                                    mine.add((String) m);
                                }
                            }
                            if (ready.isEmpty()) {
                                break;
                            }
                        }
                        if (mode.equals("lua") && mine.isEmpty()) {
                            break;
                        }
                        for (String m : mine) {
                            handled.computeIfAbsent(m, x -> new AtomicInteger()).incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();
        for (Thread t : ts) {
            t.join();
        }
        long dup = handled.values().stream().filter(x -> x.get() > 1).count();
        int total = handled.values().stream().mapToInt(AtomicInteger::get).sum();
        out("dup." + mode, "%d 个消费者抢 %d 个到期任务：处理 %d 次，覆盖 %d 个任务，被处理多次的任务 %d 个".formatted(
                consumers, n, total, handled.size(), dup));
    }

    // ---------- 3. 取出后崩溃 ----------

    /** 100 个到期任务，消费者处理前 10 个时「崩溃」（取出但没处理）；lease 为 true 时用处理中集合和回收任务。 */
    static void crash(boolean lease) throws Exception {
        String q = "delay:crash:" + lease;
        String processing = q + ":processing";
        int n = 100;
        AtomicInteger done = new AtomicInteger();
        try (Resp c = connect()) {
            c.call("DEL", q, processing);
            for (int i = 0; i < n; i++) {
                c.call("ZADD", q, 0, "t" + i);
            }
            // 第一个消费者：取出 10 个后崩溃
            long now = System.currentTimeMillis();
            if (lease) {
                c.call("EVAL", CLAIM, 2, q, processing, now, 10, now + 500);
            } else {
                c.call("EVAL", POP, 1, q, now, 10);
            }
            // 第二个消费者：正常处理剩下的，处理完从处理中集合删除；回收任务每 200 ms 运行一次
            long end = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < end) {
                long t = System.currentTimeMillis();
                if (lease) {
                    c.call("EVAL", REAP, 2, q, processing, t);
                    for (Object m : (List<?>) c.call("EVAL", CLAIM, 2, q, processing, t, 50, t + 500)) {
                        done.incrementAndGet();
                        c.call("ZREM", processing, m);
                    }
                } else {
                    done.addAndGet(((List<?>) c.call("EVAL", POP, 1, q, t, 50)).size());
                }
                Thread.sleep(200);
            }
            out(lease ? "crash.lease" : "crash.pop", "%d 个任务，第一个消费者取出 10 个后崩溃：2 秒内处理完成 %d 个，队列里还剩 %s 个，处理中集合还剩 %s 个".formatted(
                    n, done.get(), c.call("ZCARD", q), c.call("ZCARD", processing)));
        }
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
