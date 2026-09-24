import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 原子性、事务与锁实验，连接 127.0.0.1:6379，结果写成 key\tvalue 行，由 summarize.py 断言。
 *   main <输出目录>：名额扣减的五种写法、MULTI 的两类错误、锁的释放、租约过期与 fencing token
 *   before-restart <输出目录> / after-restart <输出目录>：重启前后的 EVALSHA 与 FCALL
 * 统一案例：活动名额 100 个，50 个线程各抢 10 次。
 */
public class Atomicity {

    /** Redis 返回的错误回复，与连接异常区分开。 */
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

    static final int QUOTA = 100;
    static final int THREADS = 50;
    static final int ATTEMPTS = 10;

    static final String LUA_TAKE = """
            local left = tonumber(redis.call('GET', KEYS[1]))
            if left > 0 then
              redis.call('DECR', KEYS[1])
              redis.call('RPUSH', KEYS[2], ARGV[1])
              return 1
            end
            return 0""";

    static final String FUNCTION_LIB = """
            #!lua name=quota
            redis.register_function('take', function(keys, args)
              local left = tonumber(redis.call('GET', keys[1]))
              if left > 0 then
                redis.call('DECR', keys[1])
                redis.call('RPUSH', keys[2], args[1])
                return 1
              end
              return 0
            end)""";

    static final String RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0""";

    /** 模拟下游存储的条件写：只接受 token 不小于已见最大 token 的写入，对应数据库里的 UPDATE ... WHERE fence <= ?。 */
    static final String FENCED_WRITE = """
            local seen = tonumber(redis.call('GET', KEYS[2]) or '0')
            local token = tonumber(ARGV[1])
            if token < seen then
              return 0
            end
            redis.call('SET', KEYS[2], token)
            redis.call('SET', KEYS[1], ARGV[2])
            return 1""";

    static PrintStream result;

    static void put(String key, Object value) {
        String line = key + "\t" + String.valueOf(value).replace('\n', ' ');
        result.println(line);
        System.out.println(line);
    }

    static Resp conn() throws IOException {
        return new Resp("127.0.0.1", 6379, 10_000);
    }

    public static void main(String[] args) throws Exception {
        dir = args[1];
        result = new PrintStream(Files.newOutputStream(Path.of(args[1], args[0] + ".tsv")), true, StandardCharsets.UTF_8);
        switch (args[0]) {
            case "main" -> {
                quota();
                transactions();
                locks();
            }
            case "before-restart" -> beforeRestart();
            case "after-restart" -> afterRestart();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    interface Attempt {
        /** 返回 1 表示抢到名额；retries 记录 WATCH 冲突重试次数。 */
        long take(Resp r, String user, AtomicInteger retries) throws IOException;
    }

    static void quota() throws Exception {
        String sha;
        try (Resp r = conn()) {
            r.call("FUNCTION", "LOAD", "REPLACE", FUNCTION_LIB);
            sha = (String) r.call("SCRIPT", "LOAD", LUA_TAKE);
        }
        runQuota("get-then-set", (r, user, retries) -> {
            long left = Long.parseLong((String) r.call("GET", "quota:left"));
            if (left <= 0) return 0;
            Thread.onSpinWait();
            r.call("SET", "quota:left", left - 1);
            r.call("RPUSH", "quota:winners", user);
            return 1;
        });
        runQuota("decr-then-compensate", (r, user, retries) -> {
            long left = (Long) r.call("DECR", "quota:left");
            if (left < 0) {
                r.call("INCR", "quota:left");
                return 0;
            }
            r.call("RPUSH", "quota:winners", user);
            return 1;
        });
        runQuota("watch-multi", (r, user, retries) -> {
            while (true) {
                r.call("WATCH", "quota:left");
                long left = Long.parseLong((String) r.call("GET", "quota:left"));
                if (left <= 0) {
                    r.call("UNWATCH");
                    return 0;
                }
                r.call("MULTI");
                r.call("DECR", "quota:left");
                r.call("RPUSH", "quota:winners", user);
                Object exec = r.call("EXEC");
                if (exec != null) return 1;
                retries.incrementAndGet();
            }
        });
        runQuota("lua", (r, user, retries) -> (Long) r.call("EVALSHA", sha, 2, "quota:left", "quota:winners", user));
        runQuota("function", (r, user, retries) -> (Long) r.call("FCALL", "take", 2, "quota:left", "quota:winners", user));
    }

    static void runQuota(String name, Attempt attempt) throws Exception {
        try (Resp r = conn()) {
            r.call("DEL", "quota:winners");
            r.call("SET", "quota:left", QUOTA);
        }
        AtomicInteger won = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int t = 0; t < THREADS; t++) {
            int id = t;
            Thread.ofPlatform().start(() -> {
                try (Resp r = conn()) {
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < ATTEMPTS; i++) won.addAndGet((int) attempt.take(r, "u" + id + "-" + i, retries));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        long t0 = System.nanoTime();
        go.countDown();
        done.await();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        try (Resp r = conn()) {
            put("quota." + name + ".client_won", won.get());
            put("quota." + name + ".winners_list", r.call("LLEN", "quota:winners"));
            put("quota." + name + ".left", r.call("GET", "quota:left"));
            put("quota." + name + ".watch_retries", retries.get());
            put("quota." + name + ".elapsed_ms", ms);
        }
    }

    static void transactions() throws Exception {
        try (Resp r = conn()) {
            r.call("DEL", "tx:a", "tx:k", "tx:k2");
            r.call("MULTI");
            put("tx.queue_error.set", r.call("SET", "tx:a", "1"));
            try {
                r.call("INCRBY", "tx:a");
            } catch (RedisError e) {
                put("tx.queue_error.bad_command", e.getMessage());
            }
            try {
                r.call("EXEC");
            } catch (RedisError e) {
                put("tx.queue_error.exec", e.getMessage());
            }
            put("tx.queue_error.tx:a_after", r.call("GET", "tx:a"));

            r.call("MULTI");
            r.call("SET", "tx:k", "1");
            r.call("LPUSH", "tx:k", "x");
            r.call("SET", "tx:k2", "2");
            List<Object> replies = new ArrayList<>();
            r.send("EXEC");
            replies.add(readExec(r));
            put("tx.runtime_error.exec_replies", replies.get(0));
            put("tx.runtime_error.tx:k_after", r.call("GET", "tx:k"));
            put("tx.runtime_error.tx:k2_after", r.call("GET", "tx:k2"));

            r.call("SET", "tx:a", "0");
            r.call("MULTI");
            r.call("SET", "tx:a", "99");
            put("tx.discard", r.call("DISCARD"));
            put("tx.discard.tx:a_after", r.call("GET", "tx:a"));

            try (Resp other = conn()) {
                r.call("WATCH", "tx:a");
                other.call("SET", "tx:a", "changed-by-other");
                r.call("MULTI");
                r.call("SET", "tx:a", "mine");
                put("tx.watch_conflict.exec", String.valueOf(r.call("EXEC")));
                put("tx.watch_conflict.tx:a_after", r.call("GET", "tx:a"));
            }
        }
    }

    /** EXEC 的回复是数组，其中某个元素可能是错误；逐个读取，把错误转成字符串而不是抛出。 */
    static Object readExec(Resp r) throws IOException {
        int type = r.in.read();
        String line = r.line();
        if (type != '*') throw new IOException("EXEC 返回 " + (char) type + line);
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < Integer.parseInt(line); i++) {
            try {
                items.add(r.read());
            } catch (RedisError e) {
                items.add("ERR:" + e.getMessage());
            }
        }
        return items;
    }

    static void locks() throws Exception {
        try (Resp a = conn(); Resp b = conn()) {
            a.call("DEL", "lock:order", "fence:order", "res:value", "res:fence");
            put("lock.a_acquire", a.call("SET", "lock:order", "token-a", "NX", "PX", 300));
            put("lock.b_acquire_while_held", String.valueOf(b.call("SET", "lock:order", "token-b", "NX", "PX", 300)));
            Thread.sleep(400);
            put("lock.b_acquire_after_expiry", b.call("SET", "lock:order", "token-b", "NX", "PX", 5000));
            put("lock.a_naive_del_removes_b_lock", a.call("DEL", "lock:order"));
            put("lock.holder_after_naive_del", String.valueOf(a.call("GET", "lock:order")));

            b.call("SET", "lock:order", "token-b", "PX", 5000);
            put("lock.a_token_release", a.call("EVAL", RELEASE, 1, "lock:order", "token-a"));
            put("lock.holder_after_token_release", a.call("GET", "lock:order"));
            put("lock.b_token_release", b.call("EVAL", RELEASE, 1, "lock:order", "token-b"));
        }
        leaseExpiry(false);
        leaseExpiry(true);
    }

    /** A 拿到 500ms 的锁后停顿 1500ms（模拟 GC 或长时间 I/O），B 在锁过期后拿到锁；两者先后写下游。 */
    static void leaseExpiry(boolean fencing) throws Exception {
        String tag = fencing ? "fenced" : "unfenced";
        long t0 = System.nanoTime();
        List<String> timeline = new ArrayList<>();
        Consumer<String> ev = s -> {
            synchronized (timeline) {
                timeline.add((System.nanoTime() - t0) / 1_000_000 + "ms " + s);
            }
        };
        try (Resp r = conn()) {
            r.call("DEL", "lock:order", "res:value", "res:fence");
        }
        Thread a = Thread.ofPlatform().start(() -> {
            try (Resp r = conn()) {
                r.call("SET", "lock:order", "token-a", "NX", "PX", 500);
                long fence = (Long) r.call("INCR", "fence:order");
                ev.accept("A acquired lock ttl=500ms fence=" + fence);
                Thread.sleep(1500);
                ev.accept("A resumes, lock holder is now " + r.call("GET", "lock:order"));
                Object w = fencing ? r.call("EVAL", FENCED_WRITE, 2, "res:value", "res:fence", fence, "written-by-A")
                        : r.call("SET", "res:value", "written-by-A");
                ev.accept("A writes downstream -> " + w);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(700);
        try (Resp r = conn()) {
            Object got = r.call("SET", "lock:order", "token-b", "NX", "PX", 5000);
            long fence = (Long) r.call("INCR", "fence:order");
            ev.accept("B acquired lock=" + got + " fence=" + fence);
            Object w = fencing ? r.call("EVAL", FENCED_WRITE, 2, "res:value", "res:fence", fence, "written-by-B")
                    : r.call("SET", "res:value", "written-by-B");
            ev.accept("B writes downstream -> " + w);
            a.join();
            put("lease." + tag + ".final_value", r.call("GET", "res:value"));
        }
        for (String s : timeline) put("lease." + tag + ".timeline", s);
    }

    static void beforeRestart() throws Exception {
        try (Resp r = conn()) {
            r.call("FUNCTION", "LOAD", "REPLACE", FUNCTION_LIB);
            String sha = (String) r.call("SCRIPT", "LOAD", LUA_TAKE);
            r.call("SET", "quota:left", 5);
            put("restart.sha", sha);
            put("restart.before.evalsha", r.call("EVALSHA", sha, 2, "quota:left", "quota:winners", "before"));
            put("restart.before.fcall", r.call("FCALL", "take", 2, "quota:left", "quota:winners", "before"));
            put("restart.before.function_list", r.call("FUNCTION", "LIST"));
        }
    }

    static void afterRestart() throws Exception {
        String sha = Files.readAllLines(Path.of(resultDir(), "before-restart.tsv")).stream()
                .filter(l -> l.startsWith("restart.sha\t")).findFirst().orElseThrow().split("\t")[1];
        try (Resp r = conn()) {
            try {
                put("restart.after.evalsha", r.call("EVALSHA", sha, 2, "quota:left", "quota:winners", "after"));
            } catch (RedisError e) {
                put("restart.after.evalsha", e.getMessage());
            }
            put("restart.after.fcall", r.call("FCALL", "take", 2, "quota:left", "quota:winners", "after"));
            put("restart.after.quota_left", r.call("GET", "quota:left"));
        }
    }

    static String dir;

    static String resultDir() {
        return dir;
    }
}
