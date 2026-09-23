import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 百万行导入的写入方式对比与批处理失败语义。JDK 21，MySQL Connector/J 8.0.27，单文件运行：java -cp <driver> ImportBench.java
 * 每种写入方式重新建表后写入固定行数，采样 3 次取中位数；失败语义用一批 6 行、第 4 行与第 2 行主键冲突的数据观察。
 */
public class ImportBench {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true";
    static final String USER = "root", PASSWORD = "example_password";   // 演示值，只用于本地容器
    static final String INSERT = "INSERT INTO import_order (order_no, user_id, sku, qty, remark) VALUES (?, ?, ?, ?, ?)";
    static final int ROWS = 200_000, SAMPLES = 3;

    public static void main(String[] args) throws Exception {
        System.out.println("mode\trows\tsample\tmillis\trows_per_second");
        bench("row-by-row-autocommit", 20_000, () -> rowByRow(20_000));
        bench("batch-1000-no-rewrite", ROWS, () -> batch(false, 1000, 0, ROWS));
        bench("batch-1000-rewrite", ROWS, () -> batch(true, 1000, 0, ROWS));
        bench("batch-5000-rewrite", ROWS, () -> batch(true, 5000, 0, ROWS));
        bench("4-threads-batch-1000-rewrite", ROWS, ImportBench::parallel);
        bench("load-data-local-infile", ROWS, ImportBench::loadData);
        failureSemantics(false);
        failureSemantics(true);
    }

    interface Job { void run() throws Exception; }

    static void bench(String mode, int rows, Job job) throws Exception {
        for (int s = 1; s <= SAMPLES; s++) {
            recreate();
            long t0 = System.nanoTime();
            job.run();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (count() != rows) throw new IllegalStateException(mode + " 写入行数不对：" + count());
            System.out.printf("%s\t%d\t%d\t%d\t%d%n", mode, rows, s, ms, rows * 1000L / Math.max(ms, 1));
        }
    }

    static Connection connect(boolean rewrite) throws SQLException {
        return DriverManager.getConnection(URL + "&rewriteBatchedStatements=" + rewrite + "&allowLoadLocalInfile=true", USER, PASSWORD);
    }

    static void recreate() throws SQLException {
        try (Connection c = connect(false); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS import_order");
            st.execute("""
                CREATE TABLE import_order (
                  id       BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_no BIGINT      NOT NULL,
                  user_id  BIGINT      NOT NULL,
                  sku      VARCHAR(32) NOT NULL,
                  qty      INT         NOT NULL,
                  remark   VARCHAR(64) NOT NULL,
                  UNIQUE KEY uk_order_no (order_no)
                )""");
        }
    }

    static long count() throws SQLException {
        try (Connection c = connect(false); ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM import_order")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void bind(PreparedStatement ps, long n) throws SQLException {
        ps.setLong(1, 10_000_000L + n);
        ps.setLong(2, n % 50_000);
        ps.setString(3, "SKU-" + (n % 1000));
        ps.setInt(4, (int) (n % 5) + 1);
        ps.setString(5, "remark-" + n);
    }

    static void rowByRow(int rows) throws SQLException {
        try (Connection c = connect(false); PreparedStatement ps = c.prepareStatement(INSERT)) {
            for (long n = 0; n < rows; n++) { bind(ps, n); ps.executeUpdate(); }
        }
    }

    // 每批一个事务
    static void batch(boolean rewrite, int batchSize, long from, long to) throws SQLException {
        try (Connection c = connect(rewrite); PreparedStatement ps = c.prepareStatement(INSERT)) {
            c.setAutoCommit(false);
            for (long n = from; n < to; n++) {
                bind(ps, n);
                ps.addBatch();
                if ((n - from + 1) % batchSize == 0 || n == to - 1) { ps.executeBatch(); c.commit(); }
            }
        }
    }

    static void parallel() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            long from = (long) t * ROWS / 4, to = (long) (t + 1) * ROWS / 4;
            fs.add(pool.submit(() -> { batch(true, 1000, from, to); return null; }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
    }

    static void loadData() throws Exception {
        Path csv = Path.of("/tmp/import_order.csv");
        if (!Files.exists(csv)) {
            try (BufferedWriter w = Files.newBufferedWriter(csv)) {
                for (long n = 0; n < ROWS; n++)
                    w.write((10_000_000L + n) + "," + (n % 50_000) + ",SKU-" + (n % 1000) + "," + ((n % 5) + 1) + ",remark-" + n + "\n");
            }
        }
        try (Connection c = connect(false); Statement st = c.createStatement()) {
            st.execute("LOAD DATA LOCAL INFILE '" + csv + "' INTO TABLE import_order FIELDS TERMINATED BY ',' (order_no, user_id, sku, qty, remark)");
        }
    }

    // 一批 6 行，第 4 行的 order_no 与第 2 行相同；捕获异常后故意提交，观察哪些行被写入
    static void failureSemantics(boolean rewrite) throws SQLException {
        recreate();
        long[] orderNos = {1, 2, 3, 2, 5, 6};
        int[] counts;
        try (Connection c = connect(rewrite); PreparedStatement ps = c.prepareStatement(INSERT)) {
            c.setAutoCommit(false);
            for (long no : orderNos) { bind(ps, no); ps.setLong(1, no); ps.addBatch(); }
            try {
                counts = ps.executeBatch();
            } catch (BatchUpdateException e) {
                counts = e.getUpdateCounts();
                System.out.println("# rewrite=" + rewrite + " 异常：" + e.getMessage());
            }
            c.commit();
        }
        List<Long> committed = new ArrayList<>();
        try (Connection c = connect(false); ResultSet rs = c.createStatement().executeQuery("SELECT order_no FROM import_order ORDER BY order_no")) {
            while (rs.next()) committed.add(rs.getLong(1));
        }
        System.out.println("failure\trewrite=" + rewrite + "\tupdate_counts=" + Arrays.toString(counts) + "\tcommitted=" + committed);
    }
}
