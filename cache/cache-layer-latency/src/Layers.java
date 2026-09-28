import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 同一个客户端容器里，按主键读一条商品数据，三层各自的访问延迟：Caffeine 本地缓存、Redis GET、MySQL 主键查询。
 * Redis 与 MySQL 都是单连接顺序请求，各测 20,000 次（前 2,000 次预热不计）；本地缓存测 1,000 万次取平均。
 * 输出为「键<TAB>事实」。运行：java -cp <caffeine>:<mysql-connector> src/Layers.java。
 */
public class Layers {
    static final int KEYS = 10_000;

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


    public static void main(String[] args) throws Exception {
        String value = "{\"id\":42,\"name\":\"product-42\",\"price\":1999}";
        local(value);
        redis(value);
        mysql();
    }

    static void local(String value) {
        Cache<Long, String> cache = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(30)).build();
        for (long i = 0; i < KEYS; i++) {
            cache.put(i, value);
        }
        Random r = new Random(1);
        long[] keys = new long[1 << 20];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = r.nextInt(KEYS);
        }
        long sink = 0;
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 5; round++) {                           // 取 5 轮中最快的一轮，排除 JIT 预热
            long s = System.nanoTime();
            for (int i = 0; i < 10_000_000; i++) {
                sink += cache.getIfPresent(keys[i & (keys.length - 1)]).length();
            }
            best = Math.min(best, (System.nanoTime() - s) / 10_000_000.0);
        }
        out("local", "Caffeine getIfPresent（1 万个 key 全部命中）：平均 %.1f ns/次（校验和 %d）".formatted(best, sink % 7));
    }

    static void redis(String value) throws Exception {
        try (Resp c = new Resp("redis", 6379, 5000)) {
            for (int i = 0; i < KEYS; i++) {
                c.call("SET", "product:" + i, value);
            }
            Random r = new Random(2);
            long[] t = new long[20_000];
            for (int i = 0; i < 22_000; i++) {
                String k = "product:" + r.nextInt(KEYS);
                long s = System.nanoTime();
                c.call("GET", k);
                if (i >= 2_000) {
                    t[i - 2_000] = System.nanoTime() - s;
                }
            }
            out("redis", "Redis GET（单连接顺序请求 20,000 次）：" + dist(t));
        }
    }

    static void mysql() throws Exception {
        String url = "jdbc:mysql://mysql:3306/labs?useSSL=false&allowPublicKeyRetrieval=true&useServerPrepStmts=true&cachePrepStmts=true";
        try (Connection c = DriverManager.getConnection(url, "root", "example_password")) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS product (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL, price INT NOT NULL)");
                s.execute("TRUNCATE TABLE product");
            }
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("INSERT INTO product VALUES (?, ?, ?)")) {
                for (int i = 0; i < KEYS; i++) {
                    p.setLong(1, i);
                    p.setString(2, "product-" + i);
                    p.setInt(3, 1999);
                    p.addBatch();
                }
                p.executeBatch();
            }
            c.commit();
            c.setAutoCommit(true);
            Random r = new Random(3);
            long[] t = new long[20_000];
            try (PreparedStatement q = c.prepareStatement("SELECT id, name, price FROM product WHERE id = ?")) {
                for (int i = 0; i < 22_000; i++) {
                    q.setLong(1, r.nextInt(KEYS));
                    long s = System.nanoTime();
                    try (ResultSet rs = q.executeQuery()) {
                        rs.next();
                    }
                    if (i >= 2_000) {
                        t[i - 2_000] = System.nanoTime() - s;
                    }
                }
            }
            out("mysql", "MySQL 主键查询（单连接顺序请求 20,000 次）：" + dist(t));
        }
    }

    static String dist(long[] t) {
        long[] a = t.clone();
        Arrays.sort(a);
        return "p50 %.3f ms，p99 %.3f ms".formatted(a[a.length / 2] / 1e6, a[(int) (a.length * 0.99)] / 1e6);
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
