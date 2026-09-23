import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 故障切换实验的客户端。JDK 21，MySQL Connector/J 8.0.27。用法：java -cp <driver> Failover.java <命令> <输出目录> [参数]
 *   setup <host>           建表
 *   write <run>            持续写入：每 20ms 一笔自动提交的订单，request_id 唯一；写入目标从 <run>-target 文件读取，出现 <run>-stop 文件时结束。
 *                          写入失败时不知道事务是否已提交，按同一个 request_id 用 INSERT ... ON DUPLICATE KEY UPDATE 重试，记录重试结果
 *   ambiguous              半同步等待 ACK 期间客户端超时：同一请求在无唯一键和有唯一键两张表上各重试一次
 *   reads                  写后读的策略对照
 * 连接参数 useAffectedRows=true：ON DUPLICATE KEY UPDATE 命中已有行且没有改动时返回 0，而不是 Connector/J 默认的 found rows 1
 * 事件日志同时记录 UTC 墙上时间与进程内单调时间（毫秒）。
 */
public class Failover {
    static final String USER = "root", PASSWORD = "example_password";   // 演示值，只用于本地容器
    static final long T0 = System.nanoTime();
    static Path out;

    public static void main(String[] args) throws Exception {
        out = Path.of(args[1]);
        switch (args[0]) {
            case "setup" -> setup(args[2]);
            case "write" -> write(args[2]);
            case "ambiguous" -> ambiguous();
            case "reads" -> reads();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static Connection connect(String host, int socketTimeoutMs) throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + host + ":3306/labs?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=1000&socketTimeout="
                + socketTimeoutMs + "&trackSessionState=true&useAffectedRows=true", USER, PASSWORD);
    }
    static long mono() { return (System.nanoTime() - T0) / 1_000_000; }
    static String now() { return Instant.now().toString(); }

    static void setup(String host) throws SQLException {
        try (Connection c = connect(host, 10000); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS orders (id BIGINT PRIMARY KEY AUTO_INCREMENT, request_id VARCHAR(40) NOT NULL, seq BIGINT NOT NULL, created_at DATETIME(6) NOT NULL, UNIQUE KEY uk_request (request_id))");
            st.execute("CREATE TABLE IF NOT EXISTS orders_no_key (id BIGINT PRIMARY KEY AUTO_INCREMENT, request_id VARCHAR(40) NOT NULL, created_at DATETIME(6) NOT NULL)");
        }
    }

    static String target(String run) throws Exception {
        return Files.readString(out.resolve(run + "-target")).trim();
    }

    static void write(String run) throws Exception {
        Path stop = out.resolve(run + "-stop");
        try (var log = new java.io.PrintWriter(Files.newBufferedWriter(out.resolve(run + "-client.tsv")), true)) {
            log.println("event\tutc\tmono_ms\ttarget\tseq\trequest_id\tdetail");
            log.printf("workload_started\t%s\t%d\t%s\t1\t%s-1\t%n", now(), mono(), target(run), run);
            Connection c = null;
            String host = null;
            long seq = 0;
            boolean firstAfterRetry = false;
            while (!Files.exists(stop)) {
                seq++;
                String rid = run + "-" + seq;
                try {
                    if (c == null || !host.equals(target(run))) { if (c != null) c.close(); host = target(run); c = connect(host, 3000); }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO orders (request_id, seq, created_at) VALUES (?, ?, NOW(6))")) {
                        ps.setString(1, rid); ps.setLong(2, seq); ps.executeUpdate();
                    }
                    log.printf("%s\t%s\t%d\t%s\t%d\t%s\t%n", firstAfterRetry ? "first_write_after_failover" : "client_ack", now(), mono(), host, seq, rid);
                    firstAfterRetry = false;
                } catch (SQLException e) {
                    log.printf("write_failed\t%s\t%d\t%s\t%d\t%s\t%s%n", now(), mono(), host, seq, rid, e.getClass().getSimpleName());
                    try { if (c != null) c.close(); } catch (SQLException ignored) { }
                    c = null;
                    // 结果未知：换到新的写入目标后，用同一个 request_id 幂等重试
                    while (!Files.exists(stop)) {
                        Thread.sleep(200);
                        try {
                            host = target(run);
                            c = connect(host, 3000);
                            try (PreparedStatement ps = c.prepareStatement("INSERT INTO orders (request_id, seq, created_at) VALUES (?, ?, NOW(6)) ON DUPLICATE KEY UPDATE request_id = request_id")) {
                                ps.setString(1, rid); ps.setLong(2, seq);
                                int n = ps.executeUpdate();
                                log.printf("retry_%s\t%s\t%d\t%s\t%d\t%s\t%n", n == 1 ? "inserted" : "already_committed", now(), mono(), host, seq, rid);
                            }
                            firstAfterRetry = true;
                            break;
                        } catch (SQLException retryError) {
                            try { if (c != null) c.close(); } catch (SQLException ignored) { }
                            c = null;
                        }
                    }
                }
                Thread.sleep(20);
            }
            log.printf("workload_stopped\t%s\t%d\t%s\t%d\t\t%n", now(), mono(), host, seq);
        }
    }

    // 两个线程同时写：一张表没有唯一键、一张表有唯一键。source 在等半同步 ACK，客户端 1 秒超时，随后按同一个 request_id 各重试一次
    static void ambiguous() throws Exception {
        try (var log = new java.io.PrintWriter(Files.newBufferedWriter(out.resolve("ambiguous.tsv")), true)) {
            log.println("table\tutc\tmono_ms\tevent\tdetail");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<?>> fs = new ArrayList<>();
            for (String table : List.of("orders_no_key", "orders")) {
                fs.add(pool.submit(() -> {
                    String rid = "ambiguous-" + table;
                    String insert = table.equals("orders")
                            ? "INSERT INTO orders (request_id, seq, created_at) VALUES ('" + rid + "', 0, NOW(6))"
                            : "INSERT INTO orders_no_key (request_id, created_at) VALUES ('" + rid + "', NOW(6))";
                    try (Connection c = connect("source", 1000)) {
                        log.printf("%s\t%s\t%d\tsend\t%s%n", table, now(), mono(), rid);
                        c.createStatement().executeUpdate(insert);
                        log.printf("%s\t%s\t%d\tack\t%n", table, now(), mono());
                    } catch (SQLException e) {
                        log.printf("%s\t%s\t%d\tclient_timeout\t%s%n", table, now(), mono(), e.getClass().getSimpleName());
                    }
                    Thread.sleep(4000);
                    String retry = table.equals("orders") ? insert + " ON DUPLICATE KEY UPDATE request_id = request_id" : insert;
                    try (Connection c = connect("source", 10000)) {
                        int n = c.createStatement().executeUpdate(retry);
                        log.printf("%s\t%s\t%d\tretry\taffected_rows=%d%n", table, now(), mono(), n);
                        try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table + " WHERE request_id = '" + rid + "'")) {
                            rs.next();
                            log.printf("%s\t%s\t%d\trows_for_request\t%d%n", table, now(), mono(), rs.getLong(1));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
            pool.shutdown();
        }
    }

    // 写后读：每种策略 300 次（延迟 replica 30 次）「在 source 写一行，马上按 request_id 读」，统计读不到的次数
    static void reads() throws Exception {
        try (var log = new java.io.PrintWriter(Files.newBufferedWriter(out.resolve("read-after-write.tsv")), true)) {
            log.println("strategy\treplica\tattempts\tstale_reads\tp50_read_ms\tp99_read_ms\tmax_read_ms");
            for (String replica : List.of("replica1", "replica2")) {
                for (String strategy : List.of("replica-direct", "source-sticky", "gtid-wait")) {
                    try (Connection src = connect("source", 10000); Connection rep = connect(replica, 10000)) {
                        src.createStatement().execute("SET SESSION session_track_gtids = OWN_GTID");
                        int stale = 0;
                        List<Double> lat = new ArrayList<>();
                        int attempts = replica.equals("replica2") ? 30 : 300;   // replica2 延迟 1 秒，GTID 等待每次约 1 秒
                        for (int i = 0; i < attempts; i++) {
                            String rid = "raw-" + replica + "-" + strategy + "-" + i;
                            try (PreparedStatement ps = src.prepareStatement("INSERT INTO orders (request_id, seq, created_at) VALUES (?, ?, NOW(6))")) {
                                ps.setString(1, rid); ps.setLong(2, i); ps.executeUpdate();
                            }
                            String gtid = ownGtid(src);
                            long t = System.nanoTime();
                            Connection reader = strategy.equals("source-sticky") ? src : rep;
                            if (strategy.equals("gtid-wait")) {
                                try (ResultSet rs = rep.createStatement().executeQuery("SELECT WAIT_FOR_EXECUTED_GTID_SET('" + gtid + "', 5)")) { rs.next(); }
                            }
                            try (PreparedStatement ps = reader.prepareStatement("SELECT COUNT(*) FROM orders WHERE request_id = ?")) {
                                ps.setString(1, rid);
                                try (ResultSet rs = ps.executeQuery()) { rs.next(); if (rs.getLong(1) == 0) stale++; }
                            }
                            lat.add((System.nanoTime() - t) / 1e6);
                        }
                        Collections.sort(lat);
                        log.printf("%s\t%s\t%d\t%d\t%.2f\t%.2f\t%.2f%n", strategy, strategy.equals("source-sticky") ? "source" : replica, attempts, stale,
                                lat.get(attempts / 2), lat.get((int) Math.ceil(attempts * 0.99) - 1), lat.get(attempts - 1));
                    }
                }
            }
        }
    }

    // 从 session_track_gtids = OWN_GTID 返回的会话状态中取出本连接刚提交事务的 GTID
    static String ownGtid(Connection c) throws SQLException {
        var changes = c.unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getServerSessionStateController().getSessionStateChanges();
        if (changes != null) for (var ch : changes.getSessionStateChangesList())
            if (ch.getType() == com.mysql.cj.protocol.ServerSessionStateController.SESSION_TRACK_GTIDS) return ch.getValues().get(0);
        throw new IllegalStateException("没有收到 GTID 会话状态");
    }
}
