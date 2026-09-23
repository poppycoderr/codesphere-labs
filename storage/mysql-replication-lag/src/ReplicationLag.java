import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 复制延迟实验的客户端：在 source 上持续写入带单调序号的心跳，同时按固定间隔采样两个 replica 的业务水位与复制状态。
 * JDK 21，MySQL Connector/J 8.0.27。用法：java -cp <driver> ReplicationLag.java <场景> <输出目录>
 * 场景：baseline、big-transaction、parallel、busy-replica、stall。所有时间点同时记录 UTC 墙上时间和进程内单调时间（毫秒）。
 */
public class ReplicationLag {
    static final String USER = "root", PASSWORD = "example_password";   // 演示值，只用于本地容器
    static final long T0 = System.nanoTime();
    static Path out;

    public static void main(String[] args) throws Exception {
        out = Path.of(args[1]);
        Files.createDirectories(out);
        switch (args[0]) {
            case "setup" -> setup();
            case "baseline" -> observe("baseline", 12_000, null);
            case "big-transaction" -> observe("big-transaction", 60_000, ReplicationLag::bigTransaction);
            case "stall" -> observe("stall", Integer.parseInt(args[2]), null);
            case "parallel" -> parallel();
            case "busy-replica" -> busyReplica();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static Connection connect(String host) throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + host + ":3306/labs?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=2000&socketTimeout=120000", USER, PASSWORD);
    }
    static long mono() { return (System.nanoTime() - T0) / 1_000_000; }
    static String now() { return Instant.now().toString(); }

    static void setup() throws SQLException {
        try (Connection c = connect("source"); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS heartbeat, accounts, big");
            st.execute("CREATE TABLE heartbeat (seq BIGINT PRIMARY KEY, written_at DATETIME(6) NOT NULL)");
            st.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance BIGINT NOT NULL)");
            st.execute("CREATE TABLE big (id INT PRIMARY KEY, v BIGINT NOT NULL, pad CHAR(100) NOT NULL DEFAULT '')");
            st.execute("SET SESSION cte_max_recursion_depth = 2000000");
            st.execute("INSERT INTO accounts WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 10000) SELECT n, 0 FROM s");
            st.execute("INSERT INTO big (id, v) WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 1000000) SELECT n, 0 FROM s");
        }
    }

    // ---------- 心跳写入与采样 ----------
    interface Action { void run(PrintWriter events) throws Exception; }

    static void observe(String name, int maxMillis, Action action) throws Exception {
        AtomicLong acked = new AtomicLong(maxSeq("source"));
        AtomicBoolean stop = new AtomicBoolean();
        try (PrintWriter events = new PrintWriter(Files.newBufferedWriter(out.resolve(name + "-events.tsv")), true);
             PrintWriter samples = new PrintWriter(Files.newBufferedWriter(out.resolve(name + "-samples.tsv")), true)) {
            events.println("event\tutc\tmono_ms\tdetail");
            samples.println("replica\tutc\tmono_ms\tsource_acked_seq\treplica_seq\tseq_gap\tseconds_behind_source\tio_service_state\tsql_running\treceived_not_applied\tapplying_trx_age_ms");
            Thread writer = Thread.ofPlatform().start(() -> heartbeat(acked, stop, events));
            List<Thread> samplers = new ArrayList<>();
            for (String r : List.of("replica1", "replica2")) samplers.add(Thread.ofPlatform().start(() -> sample(r, acked, stop, samples)));
            events.printf("workload_started\t%s\t%d\tfirst_seq=%d%n", now(), mono(), acked.get() + 1);
            long start = mono();
            Thread.sleep(2000);
            if (action != null) action.run(events);
            // 场景动作结束后，等两个 replica 追上（连续 2 秒没有差距）或到达最长时间
            long quietSince = -1;
            while (mono() - start < maxMillis) {
                Thread.sleep(250);
                boolean caughtUp = maxSeq("replica1") >= acked.get() - 2 && maxSeq("replica2") >= acked.get() - 2;
                if (action == null) continue;
                if (caughtUp) { if (quietSince < 0) quietSince = mono(); if (mono() - quietSince > 2000) break; } else quietSince = -1;
            }
            stop.set(true);
            writer.join();
            for (Thread t : samplers) t.join();
            events.printf("workload_stopped\t%s\t%d\tlast_acked_seq=%d%n", now(), mono(), acked.get());
        }
    }

    static long maxSeq(String host) {
        try (Connection c = connect(host); ResultSet rs = c.createStatement().executeQuery("SELECT COALESCE(MAX(seq), 0) FROM heartbeat")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) { return -1; }
    }

    // 每 50ms 在 source 上自动提交一条心跳；acked 只在 executeUpdate 返回（客户端收到提交成功）之后推进
    static void heartbeat(AtomicLong acked, AtomicBoolean stop, PrintWriter events) {
        try (Connection c = connect("source"); PreparedStatement ps = c.prepareStatement("INSERT INTO heartbeat VALUES (?, NOW(6))")) {
            while (!stop.get()) {
                long seq = acked.get() + 1;
                ps.setLong(1, seq);
                ps.executeUpdate();
                acked.set(seq);
                Thread.sleep(50);
            }
        } catch (Exception e) { events.printf("heartbeat_error\t%s\t%d\t%s%n", now(), mono(), e); }
    }

    static void sample(String replica, AtomicLong acked, AtomicBoolean stop, PrintWriter samples) {
        String sql = """
            SELECT (SELECT COALESCE(MAX(seq), 0) FROM labs.heartbeat),
                   (SELECT SERVICE_STATE FROM performance_schema.replication_connection_status),
                   (SELECT GTID_SUBTRACT(RECEIVED_TRANSACTION_SET, @@global.gtid_executed) FROM performance_schema.replication_connection_status),
                   (SELECT COALESCE(ROUND(MAX(TIMESTAMPDIFF(MICROSECOND, APPLYING_TRANSACTION_ORIGINAL_COMMIT_TIMESTAMP, UTC_TIMESTAMP(6))) / 1000), 0)
                      FROM performance_schema.replication_applier_status_by_worker WHERE APPLYING_TRANSACTION <> '')""";
        while (!stop.get()) {
            long sourceAcked = acked.get();
            try (Connection c = connect(replica); Statement st = c.createStatement()) {
                String sbs = "", sqlRunning = "";
                try (ResultSet rs = st.executeQuery("SHOW REPLICA STATUS")) {
                    if (rs.next()) { sbs = String.valueOf(rs.getString("Seconds_Behind_Source")); sqlRunning = rs.getString("Replica_SQL_Running"); }
                }
                try (ResultSet rs = st.executeQuery(sql)) {
                    rs.next();
                    long seq = rs.getLong(1);
                    samples.printf("%s\t%s\t%d\t%d\t%d\t%d\t%s\t%s\t%s\t%d\t%d%n", replica, now(), mono(), sourceAcked, seq, sourceAcked - seq,
                            sbs, rs.getString(2), sqlRunning, countGtids(rs.getString(3)), rs.getLong(4));
                }
            } catch (SQLException e) {
                samples.printf("%s\t%s\t%d\t%d\t\t\t\t\t\t\t%s%n", replica, now(), mono(), sourceAcked, e.getClass().getSimpleName());
            }
            try { Thread.sleep(250); } catch (InterruptedException e) { return; }
        }
    }

    // 解析 GTID 集合（uuid:1-5:7,uuid2:3），返回事务个数
    static long countGtids(String set) {
        if (set == null || set.isBlank()) return 0;
        long n = 0;
        for (String part : set.replace("\n", "").split(",")) {
            String[] f = part.trim().split(":");
            for (int i = 1; i < f.length; i++) {
                String[] r = f[i].split("-");
                n += r.length == 1 ? 1 : Long.parseLong(r[1]) - Long.parseLong(r[0]) + 1;
            }
        }
        return n;
    }

    static void bigTransaction(PrintWriter events) throws SQLException {
        try (Connection c = connect("source"); Statement st = c.createStatement()) {
            events.printf("big_transaction_started\t%s\t%d\tUPDATE big SET v = v + 1（1,000,000 行，一个事务）%n", now(), mono());
            long t = mono();
            int n = st.executeUpdate("UPDATE big SET v = v + 1");
            events.printf("big_transaction_committed\t%s\t%d\trows=%d source_millis=%d%n", now(), mono(), n, mono() - t);
        }
    }

    // ---------- 并行回放 ----------
    // 先停掉 replica1 的 SQL 线程，在 source 上用 16 个客户端提交 20,000 个单行事务形成积压，再启动 SQL 线程，计时直到追平
    static void parallel() throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("parallel.tsv")), true)) {
            w.println("workload\treplica_parallel_workers\ttransactions\tsource_millis\tcatch_up_millis\ttransactions_per_worker");
            for (String workload : List.of("independent", "hot-row"))
                for (int workers : new int[] {1, 4}) w.println(catchUp(workload, workers, false));
        }
    }

    static void busyReplica() throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("busy-replica.tsv")), true)) {
            w.println("workload\treplica_parallel_workers\ttransactions\tsource_millis\tcatch_up_millis\ttransactions_per_worker");
            w.println(catchUp("independent", 4, false));
            w.println(catchUp("independent", 4, true));
        }
    }

    static String catchUp(String workload, int workers, boolean busy) throws Exception {
        try (Connection r = connect("replica1"); Statement rs = r.createStatement()) {
            rs.execute("STOP REPLICA SQL_THREAD");
            rs.execute("SET GLOBAL replica_parallel_workers = " + workers);
        }
        int clients = 16, perClient = 1250;
        long t = mono();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<?>> fs = new ArrayList<>();
        for (int k = 0; k < clients; k++) {
            int client = k;
            fs.add(pool.submit(() -> {
                try (Connection c = connect("source"); PreparedStatement ps = c.prepareStatement("UPDATE accounts SET balance = balance + 1 WHERE id = ?")) {
                    for (int i = 0; i < perClient; i++) {
                        ps.setInt(1, workload.equals("hot-row") ? 1 : 1 + (client * perClient + i) % 10000);
                        ps.executeUpdate();
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        long sourceMillis = mono() - t;
        String gtid;
        try (Connection c = connect("source"); ResultSet x = c.createStatement().executeQuery("SELECT @@global.gtid_executed")) { x.next(); gtid = x.getString(1); }
        AtomicBoolean stopBusy = new AtomicBoolean();
        List<Thread> busyThreads = new ArrayList<>();
        if (busy) for (int i = 0; i < 2; i++) busyThreads.add(Thread.ofPlatform().start(() -> {
            try (Connection c = connect("replica1"); Statement st = c.createStatement()) {
                while (!stopBusy.get()) st.executeQuery("SELECT COUNT(*), SUM(CRC32(CONCAT(id, v, pad))) FROM labs.big").close();
            } catch (SQLException ignored) { }
        }));
        long catchUp;
        try (Connection r = connect("replica1"); Statement rs = r.createStatement()) {
            rs.execute("TRUNCATE TABLE performance_schema.events_transactions_summary_by_thread_by_event_name");
            long s = mono();
            rs.execute("START REPLICA SQL_THREAD");
            try (ResultSet x = rs.executeQuery("SELECT WAIT_FOR_EXECUTED_GTID_SET('" + gtid + "', 300)")) { x.next(); if (x.getInt(1) != 0) throw new IllegalStateException("追平超时"); }
            catchUp = mono() - s;
            stopBusy.set(true);
            StringBuilder dist = new StringBuilder();
            try (ResultSet x = rs.executeQuery("""
                    SELECT s.COUNT_STAR FROM performance_schema.events_transactions_summary_by_thread_by_event_name s
                    JOIN performance_schema.threads t USING (THREAD_ID)
                    WHERE t.NAME = 'thread/sql/replica_worker' ORDER BY s.COUNT_STAR DESC""")) {
                while (x.next()) dist.append(dist.isEmpty() ? "" : ",").append(x.getLong(1));
            }
            for (Thread b : busyThreads) b.join();
            return String.format("%s%s\t%d\t%d\t%d\t%d\t%s", workload, busy ? "+replica-query-load" : "", workers, clients * perClient, sourceMillis, catchUp, dist);
        }
    }
}
