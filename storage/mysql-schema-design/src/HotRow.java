import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 热点行更新：N 个线程各执行 400 次自动提交的扣减，对比同一行、分散到 1,000 行、同一 SKU 拆成 4 或 16 个桶。
 * JDK 21，MySQL Connector/J 8.0.27；锁等待次数取 Innodb_row_lock_waits 的增量。
 */
public class HotRow {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true";
    static final String USER = "root", PASSWORD = "example_password";   // 演示值，只用于本地容器
    static final int PER_THREAD = 400;

    public static void main(String[] args) throws Exception {
        setup();
        System.out.println("mode\tthreads\tupdates\tmillis\ttps\tmax_latency_ms\trow_lock_waits");
        for (int threads : new int[] {1, 16, 64}) {
            run("same-row", threads, (t, i, r) -> new long[] {1, 0});
            run("spread-1000", threads, (t, i, r) -> new long[] {1 + (t * PER_THREAD + i) % 1000, 0});
        }
        run("buckets-4", 64, (t, i, r) -> new long[] {1, r.nextInt(4)});
        run("buckets-16", 64, (t, i, r) -> new long[] {1, r.nextInt(16)});
    }

    interface Target { long[] pick(int thread, int i, Random r); }

    static Connection connect() throws SQLException { return DriverManager.getConnection(URL, USER, PASSWORD); }

    static void setup() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS sku_stock_bucket");
            st.execute("CREATE TABLE sku_stock_bucket (sku_id BIGINT NOT NULL, bucket_no TINYINT NOT NULL, qty INT NOT NULL, PRIMARY KEY (sku_id, bucket_no))");
            // 1,000 个 SKU 各一个 0 号桶；SKU 1 另外拆出 1—15 号桶，库存足够大，不会扣完
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sku_stock_bucket VALUES (?, ?, 100000000)")) {
                for (int sku = 1; sku <= 1000; sku++) { ps.setLong(1, sku); ps.setInt(2, 0); ps.addBatch(); }
                for (int b = 1; b < 16; b++) { ps.setLong(1, 1); ps.setInt(2, b); ps.addBatch(); }
                ps.executeBatch();
            }
        }
    }

    static long lockWaits() throws SQLException {
        try (Connection c = connect(); ResultSet rs = c.createStatement().executeQuery("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'")) {
            rs.next();
            return rs.getLong(2);
        }
    }

    static void run(String mode, int threads, Target target) throws Exception {
        long waits0 = lockWaits();
        AtomicLong maxNanos = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            fs.add(pool.submit(() -> {
                Random r = new Random(42 + thread);
                try (Connection c = connect();
                     PreparedStatement ps = c.prepareStatement("UPDATE sku_stock_bucket SET qty = qty - 1 WHERE sku_id = ? AND bucket_no = ? AND qty > 0")) {
                    start.await();
                    for (int i = 0; i < PER_THREAD; i++) {
                        long[] k = target.pick(thread, i, r);
                        ps.setLong(1, k[0]);
                        ps.setLong(2, k[1]);
                        long t0 = System.nanoTime();
                        if (ps.executeUpdate() != 1) throw new IllegalStateException("扣减失败");
                        maxNanos.accumulateAndGet(System.nanoTime() - t0, Math::max);
                    }
                }
                return null;
            }));
        }
        long t0 = System.nanoTime();
        start.countDown();
        for (Future<?> f : fs) f.get();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        long updates = (long) threads * PER_THREAD;
        System.out.printf("%s\t%d\t%d\t%d\t%d\t%.1f\t%d%n", mode, threads, updates, ms, updates * 1000 / Math.max(ms, 1),
                maxNanos.get() / 1e6, lockWaits() - waits0);
    }
}
