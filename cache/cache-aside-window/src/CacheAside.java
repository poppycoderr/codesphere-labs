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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存与数据库之间的不一致窗口，用闩锁控制读写两个线程的交错顺序，输出为「键<TAB>事实」：
 * 1. Cache Aside：读请求回源读到旧值后停住，写请求更新库并删缓存，读请求再回填旧值；
 * 2. 在事务里删缓存：删除和提交之间，读请求回源读到提交前的旧值并回填；
 * 3. 延迟双删：第二次删除在回填之后（读快于延迟）与之前（读慢于延迟）；
 * 4. 写入标记：写入期间设置标记，读请求回源后发现标记，不回填；
 * 5. 不加控制的自然并发：2,000 轮读写同时开始，统计结束后缓存与库不一致的轮数。
 * 运行：java -cp <mysql-connector> src/CacheAside.java，连接 redis:6379 与 mysql:3306。
 */
public class CacheAside {
    static final String URL = "jdbc:mysql://mysql:3306/labs?useSSL=false&allowPublicKeyRetrieval=true";

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


    static Connection db() throws Exception {
        return DriverManager.getConnection(URL, "root", "example_password");
    }

    static Resp redis() throws IOException {
        return new Resp("redis", 6379, 5000);
    }

    public static void main(String[] args) throws Exception {
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS product (id BIGINT PRIMARY KEY, price INT NOT NULL)");
        }
        cacheAside();
        deleteInsideTx();
        delayedDoubleDelete(200, "fast");
        delayedDoubleDelete(800, "slow");
        writeMarker();
        natural();
    }

    static void reset(int price) throws Exception {
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute("REPLACE INTO product VALUES (1, " + price + ")");
        }
        try (Resp r = redis()) {
            r.call("DEL", "product:1", "product:1:writing");
        }
    }

    static int dbPrice(Connection c) throws Exception {
        try (PreparedStatement p = c.prepareStatement("SELECT price FROM product WHERE id = 1"); ResultSet rs = p.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static String state() throws Exception {
        try (Connection c = db(); Resp r = redis()) {
            return "库里 %d，缓存里 %s（TTL %s 秒）".formatted(dbPrice(c), r.call("GET", "product:1"), r.call("TTL", "product:1"));
        }
    }

    // ---------- 1. Cache Aside ----------

    static void cacheAside() throws Exception {
        reset(100);
        CountDownLatch readDone = new CountDownLatch(1);
        CountDownLatch writeDone = new CountDownLatch(1);
        Thread reader = Thread.ofPlatform().start(() -> {
            try (Connection c = db(); Resp r = redis()) {
                if (r.call("GET", "product:1") == null) {
                    int old = dbPrice(c);                           // t1：未命中，回源读到 100
                    readDone.countDown();
                    writeDone.await();                              // 读请求在这里慢了
                    r.call("SET", "product:1", old, "EX", 600);     // t5：回填旧值
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        readDone.await();
        try (Connection c = db(); Resp r = redis(); Statement s = c.createStatement()) {
            s.execute("UPDATE product SET price = 200 WHERE id = 1");    // t2—t3：更新库（自动提交）
            r.call("DEL", "product:1");                                 // t4：删缓存
        }
        writeDone.countDown();
        reader.join();
        out("cache_aside", "读请求回源读到旧值后停住，写请求更新为 200 并删缓存，读请求再回填：" + state());
    }

    // ---------- 2. 事务内删缓存 ----------

    static void deleteInsideTx() throws Exception {
        reset(100);
        try (Connection w = db(); Resp r = redis()) {
            w.setAutoCommit(false);
            try (Statement s = w.createStatement()) {
                s.execute("UPDATE product SET price = 200 WHERE id = 1");
            }
            r.call("DEL", "product:1");                                 // 事务还没提交就删了缓存
            try (Connection rc = db(); Resp rr = redis()) {             // 这时来了一个读请求
                if (rr.call("GET", "product:1") == null) {
                    rr.call("SET", "product:1", dbPrice(rc), "EX", 600);
                }
            }
            w.commit();
        }
        out("delete_inside_tx", "事务内删缓存、提交前有读请求回源回填：提交后" + state());
    }

    // ---------- 3. 延迟双删 ----------

    static void delayedDoubleDelete(int readerExtraMs, String label) throws Exception {
        reset(100);
        CountDownLatch readDone = new CountDownLatch(1);
        CountDownLatch firstDelete = new CountDownLatch(1);
        Thread reader = Thread.ofPlatform().start(() -> {
            try (Connection c = db(); Resp r = redis()) {
                if (r.call("GET", "product:1") == null) {
                    int old = dbPrice(c);
                    readDone.countDown();
                    firstDelete.await();
                    Thread.sleep(readerExtraMs);                        // 读请求在第一次删除后还要多久才回填
                    r.call("SET", "product:1", old, "EX", 600);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        readDone.await();
        try (Connection c = db(); Resp r = redis(); Statement s = c.createStatement()) {
            s.execute("UPDATE product SET price = 200 WHERE id = 1");
            r.call("DEL", "product:1");
            firstDelete.countDown();
            Thread.sleep(500);                                          // 延迟 500 ms 再删一次
            r.call("DEL", "product:1");
        }
        reader.join();
        out("double_delete." + label, "延迟 500 ms 双删，读请求在第一次删除后 %d ms 回填：%s".formatted(readerExtraMs, state()));
    }

    // ---------- 4. 写入标记 ----------

    static void writeMarker() throws Exception {
        reset(100);
        CountDownLatch readDone = new CountDownLatch(1);
        CountDownLatch writeDone = new CountDownLatch(1);
        AtomicInteger skipped = new AtomicInteger();
        Thread reader = Thread.ofPlatform().start(() -> {
            try (Connection c = db(); Resp r = redis()) {
                if (r.call("GET", "product:1") == null) {
                    int old = dbPrice(c);
                    readDone.countDown();
                    writeDone.await();
                    if (r.call("EXISTS", "product:1:writing").equals(1L)) {   // 回填前确认没有写入在进行
                        skipped.incrementAndGet();
                    } else {
                        r.call("SET", "product:1", old, "EX", 600);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        readDone.await();
        try (Connection c = db(); Resp r = redis(); Statement s = c.createStatement()) {
            r.call("SET", "product:1:writing", 1, "PX", 2000);        // 写入标记，TTL 覆盖主从延迟
            s.execute("UPDATE product SET price = 200 WHERE id = 1");
            r.call("DEL", "product:1");
        }
        writeDone.countDown();
        reader.join();
        out("write_marker", "写入期间设置标记，读请求回填前发现标记，跳过回填 %d 次：%s".formatted(skipped.get(), state()));
    }

    // ---------- 5. 自然并发 ----------

    static void natural() throws Exception {
        int rounds = 2000;
        int stale = 0;
        try (Connection wc = db(); Connection rc = db(); Resp wr = redis(); Resp rr = redis();
             PreparedStatement up = wc.prepareStatement("UPDATE product SET price = ? WHERE id = 1")) {
            for (int i = 0; i < rounds; i++) {
                reset(i * 2);
                CountDownLatch go = new CountDownLatch(1);
                int newPrice = i * 2 + 1;
                Thread reader = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        if (rr.call("GET", "product:1") == null) {
                            rr.call("SET", "product:1", dbPrice(rc), "EX", 600);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                go.countDown();
                up.setInt(1, newPrice);
                up.executeUpdate();
                wr.call("DEL", "product:1");
                reader.join();
                Object cached = wr.call("GET", "product:1");
                if (cached != null && Integer.parseInt((String) cached) != newPrice) {
                    stale++;
                }
            }
        }
        out("natural", "读写同时开始、不加控制，%,d 轮中结束后缓存仍是旧值的轮数：%d".formatted(rounds, stale));
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
