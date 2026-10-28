import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分桶库存：总额拆到多个桶、空桶时的误判售罄，以及调整桶数（布局）时的迁移。
 * 所有键带 {sku1} 哈希标签，集群模式下落在同一个槽，迁移脚本可以原子地读写全部桶。
 */
public class BucketLayout {

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




    static Resp redis() throws IOException {
        return new Resp("redis", 6379, 5000);
    }

    static String bucket(int i) {
        return "stock:{sku1}:b" + i;
    }

    static final String LAYOUT = "layout:{sku1}";

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    /** 把 total 拆成 k 份：余数分给前面的桶。 */
    static long[] split(long total, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("桶数必须大于 0：" + k);
        }
        long[] parts = new long[k];
        for (int i = 0; i < k; i++) {
            parts[i] = total / k + (i < total % k ? 1 : 0);
        }
        return parts;
    }

    static long sum(Resp r, int k) throws IOException {
        long s = 0;
        for (int i = 0; i < k; i++) {
            Object v = r.call("GET", bucket(i));
            s += v == null ? 0 : Long.parseLong((String) v);
        }
        return s;
    }

    static void init(Resp r, long total, int k) throws IOException {
        r.call("FLUSHALL");
        long[] parts = split(total, k);
        for (int i = 0; i < k; i++) {
            r.call("SET", bucket(i), parts[i]);
        }
        r.call("HSET", LAYOUT, "version", 1, "k", k);
    }

    // 扣减：调用方带上它看到的布局版本；版本不符返回 -2（让调用方重读布局），桶空返回 -1
    static final String DEDUCT = """
            if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return -2 end
            local s = tonumber(redis.call('GET', KEYS[2]) or '-1')
            if s <= 0 then return -1 end
            return redis.call('DECR', KEYS[2])""";

    // 迁移：在一个脚本里读全部旧桶、按新桶数拆分、删掉多余的桶、版本号加一
    static final String MIGRATE = """
            local k = tonumber(redis.call('HGET', KEYS[1], 'k'))
            local nk = tonumber(ARGV[1])
            if nk <= 0 then return redis.error_reply('bad bucket count') end
            local total = 0
            for i = 0, k - 1 do total = total + tonumber(redis.call('GET', ARGV[2] .. i) or '0') end
            local maxk = math.max(k, nk)
            for i = 0, maxk - 1 do
              if i < nk then
                local part = math.floor(total / nk)
                if i < total % nk then part = part + 1 end
                redis.call('SET', ARGV[2] .. i, part)
              else
                redis.call('DEL', ARGV[2] .. i)
              end
            end
            redis.call('HSET', KEYS[1], 'k', nk)
            return redis.call('HINCRBY', KEYS[1], 'version', 1)""";

    static long[] layout(Resp r) throws IOException {
        List<?> v = (List<?>) r.call("HMGET", LAYOUT, "version", "k");
        return new long[] {Long.parseLong((String) v.get(0)), Long.parseLong((String) v.get(1))};
    }

    static void splitting() {
        out("split.integer-division", "10 件拆 3 个桶，每桶 10/3：合计 " + 3 * (10 / 3));
        long[] p = split(10, 3);
        out("split.remainder", "余数分给前面的桶：" + p[0] + "/" + p[1] + "/" + p[2] + "，合计 " + (p[0] + p[1] + p[2]));
        long[] q = split(3, 5);
        out("split.more-buckets", "3 件拆 5 个桶：" + java.util.Arrays.toString(q) + "，有 " + java.util.Arrays.stream(q).filter(x -> x == 0).count() + " 个空桶");
        try {
            split(10, 0);
        } catch (IllegalArgumentException e) {
            out("split.zero", "0 个桶：拒绝（" + e.getMessage() + "）");
        }
    }

    /** 只扣自己的桶：桶空就判售罄。修正：桶空时依次试其他桶。 */
    static void emptyBucket() throws Exception {
        try (Resp r = redis()) {
            for (boolean probe : new boolean[] {false, true}) {
                init(r, 10, 3);                       // 4/3/3
                int ok = 0;
                int soldOut = 0;
                long leftWhenSoldOut = 0;
                for (int n = 0; n < 10; n++) {
                    int home = n < 6 ? 0 : 1;              // 请求不均匀：6 个落在 0 号桶，4 个落在 1 号桶
                    boolean done = false;
                    for (int t = 0; t < (probe ? 3 : 1) && !done; t++) {
                        long res = (Long) r.call("EVAL", DEDUCT, 2, LAYOUT, bucket((home + t) % 3), 1);
                        if (res >= 0) {
                            ok++;
                            done = true;
                        }
                    }
                    if (!done) {
                        soldOut++;
                        leftWhenSoldOut = Math.max(leftWhenSoldOut, sum(r, 3));
                    }
                }
                out("empty." + (probe ? "probe" : "home-only"), (probe ? "空桶时依次试其他桶" : "只扣自己的桶") + "：10 个请求抢 10 件（4/3/3），成功 " + ok
                        + "，判为售罄 " + soldOut + "，判售罄时其他桶里还有 " + leftWhenSoldOut + " 件");
            }
        }
    }

    /** 客户端迁移：读出总数 → 这期间有 3 笔扣减 → 按读到的总数写新桶。 */
    static void clientSideMigration() throws Exception {
        try (Resp r = redis()) {
            init(r, 10, 3);
            long total = sum(r, 3);
            int sold = 0;
            for (int i = 0; i < 3; i++) {
                if ((Long) r.call("EVAL", DEDUCT, 2, LAYOUT, bucket(i), 1) >= 0) {
                    sold++;
                }
            }
            long[] parts = split(total, 5);
            for (int i = 0; i < 5; i++) {
                r.call("SET", bucket(i), parts[i]);
            }
            r.call("HSET", LAYOUT, "k", 5);
            out("migrate.client-side", "迁移读总数后卖出 " + sold + " 件，写新桶后库存 " + sum(r, 5) + "：总共能卖 " + (sold + sum(r, 5)) + " 件（初始 10）");
        }
    }

    /** 缩容 5 → 3 后，旧路由的请求还按 5 个桶算，扣到了已删除的 4 号桶。 */
    static void staleRoute() throws Exception {
        try (Resp r = redis()) {
            init(r, 10, 5);
            long[] seen = layout(r);                                  // 请求先读到旧布局
            r.call("EVAL", MIGRATE, 1, LAYOUT, 3, "stock:{sku1}:b");
            // 没有版本校验：直接 DECR 旧路由算出的 4 号桶
            long naive = (Long) r.call("DECR", bucket(4));
            out("stale.no-version", "不校验版本，DECR 已删除的 4 号桶得到 " + naive + "，这笔扣减不在任何现存的桶里；现存库存仍是 " + sum(r, 3));
            r.call("DEL", bucket(4));
            long res = (Long) r.call("EVAL", DEDUCT, 2, LAYOUT, bucket(4), seen[0]);
            long[] now = layout(r);
            long retry = (Long) r.call("EVAL", DEDUCT, 2, LAYOUT, bucket(4 % (int) now[1]), now[0]);
            out("stale.versioned", "带版本校验：旧版本请求返回 " + res + "（被拒），重读布局（版本 " + now[0] + "、" + now[1] + " 个桶）后扣减成功，剩余 " + sum(r, 3));
        }
    }

    /** 并发扣减的同时多次调整桶数：卖出总数必须恰好等于初始库存。 */
    static void concurrent() throws Exception {
        try (Resp r = redis()) {
            init(r, 1000, 4);
        }
        AtomicInteger sold = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();
        AtomicBoolean done = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(9);
        List<Future<?>> fs = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            int seed = w;
            fs.add(pool.submit(() -> {
                try (Resp r = redis()) {
                    int n = seed;
                    while (true) {
                        long[] lay = layout(r);
                        int k = (int) lay[1];
                        boolean got = false;
                        boolean retry = false;
                        for (int t = 0; t < k; t++) {
                            long res = (Long) r.call("EVAL", DEDUCT, 2, LAYOUT, bucket((n + t) % k), lay[0]);
                            if (res == -2) {
                                stale.incrementAndGet();
                                retry = true;
                                break;
                            }
                            if (res >= 0) {
                                got = true;
                                break;
                            }
                        }
                        n++;
                        if (got) {
                            sold.incrementAndGet();
                        } else if (!retry) {
                            return null;                          // 所有桶都空：售罄
                        }
                    }
                }
            }));
        }
        fs.add(pool.submit(() -> {
            try (Resp r = redis()) {
                for (int nk : new int[] {7, 2, 5, 1, 3}) {
                    Thread.sleep(15);
                    r.call("EVAL", MIGRATE, 1, LAYOUT, nk, "stock:{sku1}:b");
                }
            }
            done.set(true);
            return null;
        }));
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        try (Resp r = redis()) {
            long[] lay = layout(r);
            long negative = 0;
            for (int i = 0; i < 8; i++) {
                Object v = r.call("GET", bucket(i));
                if (v != null && Long.parseLong((String) v) < 0) {
                    negative++;
                }
            }
            out("concurrent", "8 个线程扣减 1000 件，期间桶数调整 5 次：卖出 " + sold.get() + "，剩余 " + sum(r, (int) lay[1]) + "，负数桶 " + negative
                    + "，最终版本 " + lay[0]);
            System.err.println("stale_rejections=" + stale.get());
        }
    }

    public static void main(String[] args) throws Exception {
        splitting();
        emptyBucket();
        clientSideMigration();
        staleRoute();
        concurrent();
    }
}
