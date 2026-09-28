import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.locks.LockSupport;

/**
 * 1,000 条订单补上用户名：逐条查询（N+1）、先去重再逐条查询、一次 IN 批量查询，以及同库 JOIN 作为对照。
 * 连接分两种：直连 MySQL（回环地址，往返接近 0），以及经过一个每个方向延迟约 0.5 ms 的 TCP 代理（模拟同机房约 1 ms 的往返）。
 * 输出为「键<TAB>事实」。运行：java -cp <mysql-connector> src/BatchJoin.java，连接 127.0.0.1:3306/labs。
 */
public class BatchJoin {
    static final int ORDERS = 1000;
    static final int CUSTOMERS = 200;
    static final int PROXY_PORT = 3307;

    record Order(long id, long customerId, int amount) {
    }

    interface Method {
        int run(Connection c, List<Order> orders) throws SQLException;
    }

    public static void main(String[] args) throws Exception {
        seed();
        startProxy(PROXY_PORT, 500_000);
        Map<String, Method> methods = new java.util.LinkedHashMap<>();
        methods.put("n_plus_one", BatchJoin::nPlusOne);
        methods.put("dedup_then_each", BatchJoin::dedupThenEach);
        methods.put("batch_in", BatchJoin::batchIn);
        methods.put("join", (c, orders) -> join(c));
        for (int port : new int[] {3306, PROXY_PORT}) {
            try (Connection c = DriverManager.getConnection(url(port), "root", "example_password")) {
                List<Order> orders = loadOrders(c);
                for (var e : methods.entrySet()) {
                    for (int i = 0; i < 3; i++) {
                        e.getValue().run(c, orders);                   // 预热
                    }
                    long[] ms = new long[5];
                    int[] queries = new int[1];
                    for (int i = 0; i < 5; i++) {
                        long s = System.nanoTime();
                        queries[0] = e.getValue().run(c, orders);
                        ms[i] = (System.nanoTime() - s) / 1000;
                    }
                    Arrays.sort(ms);
                    out((port == 3306 ? "direct." : "rtt1ms.") + e.getKey(), "%s：%d 次查询，耗时 %.1f ms（5 次中位数）".formatted(
                            port == 3306 ? "直连" : "约 1 ms 往返", queries[0], ms[2] / 1000.0));
                }
            }
        }
        System.exit(0);
    }

    static String url(int port) {
        return "jdbc:mysql://127.0.0.1:" + port + "/labs?useSSL=false&allowPublicKeyRetrieval=true";
    }

    static void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(url(3306) + "&rewriteBatchedStatements=true", "root", "example_password");
             Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE customer");
            s.execute("TRUNCATE TABLE orders");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO customer VALUES (?, ?)")) {
                for (int i = 1; i <= CUSTOMERS; i++) {
                    p.setLong(1, i);
                    p.setString(2, "customer-" + i);
                    p.addBatch();
                }
                p.executeBatch();
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO orders VALUES (?, ?, ?)")) {
                for (int i = 1; i <= ORDERS; i++) {
                    p.setLong(1, i);
                    p.setLong(2, (i * 7L) % CUSTOMERS + 1);        // 1,000 条订单只涉及 200 个用户
                    p.setInt(3, i % 997);
                    p.addBatch();
                }
                p.executeBatch();
            }
        }
    }

    static List<Order> loadOrders(Connection c) throws SQLException {
        List<Order> orders = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, customer_id, amount FROM orders ORDER BY id")) {
            while (rs.next()) {
                orders.add(new Order(rs.getLong(1), rs.getLong(2), rs.getInt(3)));
            }
        }
        return orders;
    }

    static int nPlusOne(Connection c, List<Order> orders) throws SQLException {
        Map<Long, String> names = new HashMap<>();
        try (PreparedStatement p = c.prepareStatement("SELECT name FROM customer WHERE id = ?")) {
            for (Order o : orders) {
                p.setLong(1, o.customerId());
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    names.put(o.id(), rs.getString(1));
                }
            }
        }
        check(names.size() == ORDERS);
        return orders.size();
    }

    static int dedupThenEach(Connection c, List<Order> orders) throws SQLException {
        Set<Long> ids = new LinkedHashSet<>();
        orders.forEach(o -> ids.add(o.customerId()));
        Map<Long, String> byId = new HashMap<>();
        try (PreparedStatement p = c.prepareStatement("SELECT name FROM customer WHERE id = ?")) {
            for (long id : ids) {
                p.setLong(1, id);
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    byId.put(id, rs.getString(1));
                }
            }
        }
        check(orders.stream().allMatch(o -> byId.containsKey(o.customerId())));
        return ids.size();
    }

    static int batchIn(Connection c, List<Order> orders) throws SQLException {
        Set<Long> ids = new LinkedHashSet<>();
        orders.forEach(o -> ids.add(o.customerId()));
        StringJoiner in = new StringJoiner(",", "(", ")");
        ids.forEach(x -> in.add("?"));
        Map<Long, String> byId = new HashMap<>();
        try (PreparedStatement p = c.prepareStatement("SELECT id, name FROM customer WHERE id IN " + in)) {
            int k = 1;
            for (long id : ids) {
                p.setLong(k++, id);
            }
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    byId.put(rs.getLong(1), rs.getString(2));
                }
            }
        }
        check(orders.stream().allMatch(o -> byId.containsKey(o.customerId())));
        return 1;
    }

    static int join(Connection c) throws SQLException {
        int n = 0;
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT o.id, c.name FROM orders o JOIN customer c ON c.id = o.customer_id")) {
            while (rs.next()) {
                n++;
            }
        }
        check(n == ORDERS);
        return 1;
    }

    static void check(boolean ok) {
        if (!ok) {
            throw new IllegalStateException("拼装结果不完整");
        }
    }

    // ---------- 延迟代理 ----------

    /** 每个方向把读到的数据延迟 delayNanos 后再转发，模拟网络往返。 */
    static void startProxy(int port, long delayNanos) throws IOException {
        ServerSocket server = new ServerSocket(port);
        Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                try {
                    Socket client = server.accept();
                    Socket upstream = new Socket("127.0.0.1", 3306);
                    client.setTcpNoDelay(true);
                    upstream.setTcpNoDelay(true);
                    pump(client.getInputStream(), upstream.getOutputStream(), delayNanos);
                    pump(upstream.getInputStream(), client.getOutputStream(), delayNanos);
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    static void pump(InputStream in, OutputStream out, long delayNanos) {
        Thread.ofPlatform().daemon().start(() -> {
            byte[] buf = new byte[64 * 1024];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    long until = System.nanoTime() + delayNanos;
                    while (System.nanoTime() < until) {
                        LockSupport.parkNanos(50_000);
                    }
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // 连接关闭
            }
        });
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
