import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * 深分页与索引的写入代价，输出为「键<TAB>事实」，执行计划写入参数给出的目录：
 * 1. 同一张 50 万行的订单表，LIMIT offset 与游标分页在不同深度下读取多少行、耗时多少；
 * 2. 按时间排序时用 (created_at, id) 做游标；
 * 3. 同样写入 20 万行，表上有 0、2、5 个二级索引时的耗时与索引大小。
 */
public class Pagination {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
    static Path out;

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        seed();
        pagination();
        timeOrdered();
        writeCost();
    }

    static void seed() throws Exception {
        exec("DROP TABLE IF EXISTS orders",
             "CREATE TABLE orders (id BIGINT PRIMARY KEY, merchant_id INT NOT NULL, created_at DATETIME(3) NOT NULL, status TINYINT NOT NULL, "
                     + "amount_cents INT NOT NULL, note VARCHAR(100) NOT NULL, KEY idx_created (created_at, id))");
        Random random = new Random(11);
        long base = Timestamp.valueOf("2026-01-01 00:00:00").getTime();
        try (Connection c = conn(); PreparedStatement p = c.prepareStatement("INSERT INTO orders VALUES (?,?,?,?,?,?)")) {
            c.setAutoCommit(false);
            for (int i = 1; i <= 500_000; i++) {
                p.setLong(1, i); p.setInt(2, 1 + random.nextInt(200)); p.setTimestamp(3, new Timestamp(base + i * 60_000L + random.nextInt(60_000)));
                p.setInt(4, random.nextInt(4)); p.setInt(5, 100 + random.nextInt(100_000)); p.setString(6, "note-" + random.nextInt(1_000_000));
                p.addBatch();
                if (i % 5000 == 0) { p.executeBatch(); c.commit(); }
            }
        }
        exec("ANALYZE TABLE orders");
        System.out.printf("seed\torders 表 %,d 行，主键 id，二级索引 (created_at, id)%n", count("SELECT COUNT(*) FROM orders"));
    }

    static void pagination() throws Exception {
        StringBuilder plans = new StringBuilder();
        for (int offset : new int[] {0, 10_000, 100_000, 400_000}) {
            String offsetSql = "SELECT id, merchant_id, amount_cents, note FROM orders ORDER BY id LIMIT " + offset + ", 20";
            long lastId = offset; // id 从 1 连续，上一页最后一个 id 就是 offset
            String keysetSql = "SELECT id, merchant_id, amount_cents, note FROM orders WHERE id > " + lastId + " ORDER BY id LIMIT 20";
            boolean same = rows(offsetSql).equals(rows(keysetSql));
            long[] o = readAndTime(offsetSql), k = readAndTime(keysetSql);
            plans.append("## offset=").append(offset).append("\n").append(offsetSql).append("\n").append(explain(offsetSql)).append("\n")
                 .append(keysetSql).append("\n").append(explain(keysetSql)).append("\n");
            System.out.printf("page.%d\t第 %,d 行之后的 20 行：LIMIT offset 读取 %,d 行、平均 %.2fms；游标 WHERE id > ? 读取 %,d 行、平均 %.2fms；结果相同=%s%n",
                    offset, offset, o[0], o[1] / 1000.0, k[0], k[1] / 1000.0, same);
        }
        Files.writeString(out.resolve("pagination-plans.txt"), plans.toString());
    }

    static void timeOrdered() throws Exception {
        // 按时间倒序翻页：游标是上一页最后一行的 (created_at, id)；比较行构造器写法与展开写法
        StringBuilder plans = new StringBuilder();
        for (String form : List.of("row", "expanded")) {
            List<String> page = new ArrayList<>();
            Timestamp lastTs = null; long lastId = 0;
            long totalRead = 0, lastPageRead = 0;
            String sql = null;
            for (int pages = 0; pages < 50; pages++) {
                sql = lastTs == null
                        ? "SELECT id, created_at FROM orders ORDER BY created_at DESC, id DESC LIMIT 20"
                        : form.equals("row")
                        ? "SELECT id, created_at FROM orders WHERE (created_at, id) < ('" + lastTs + "', " + lastId + ") ORDER BY created_at DESC, id DESC LIMIT 20"
                        : "SELECT id, created_at FROM orders WHERE created_at < '" + lastTs + "' OR (created_at = '" + lastTs + "' AND id < " + lastId + ") ORDER BY created_at DESC, id DESC LIMIT 20";
                lastPageRead = handlerReads(sql);
                totalRead += lastPageRead;
                try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                    while (r.next()) { lastId = r.getLong(1); lastTs = r.getTimestamp(2); page.add(lastId + ""); }
                }
            }
            plans.append("## ").append(form).append("\n").append(sql).append("\n").append(explain(sql)).append("\n");
            System.out.printf("time_cursor.%s\t%s：倒序翻 50 页取到 %,d 行、不重复 %,d 行，总共读取 %,d 行，第 50 页读取 %,d 行%n",
                    form, form.equals("row") ? "WHERE (created_at, id) < (?, ?)" : "WHERE created_at < ? OR (created_at = ? AND id < ?)",
                    page.size(), new HashSet<>(page).size(), totalRead, lastPageRead);
        }
        Files.writeString(out.resolve("time-cursor-plans.txt"), plans.toString());
    }

    static void writeCost() throws Exception {
        String[][] variants = {
                {"0", ""},
                {"2", ", KEY idx_merchant (merchant_id), KEY idx_created (created_at)"},
                {"5", ", KEY idx_merchant (merchant_id), KEY idx_created (created_at), KEY idx_status_created (status, created_at), KEY idx_amount (amount_cents), KEY idx_note (note)"}};
        for (String[] v : variants) {
            List<Long> times = new ArrayList<>();
            String size = "";
            for (int round = 0; round < 3; round++) {
                exec("DROP TABLE IF EXISTS orders_w",
                     "CREATE TABLE orders_w (id BIGINT PRIMARY KEY, merchant_id INT NOT NULL, created_at DATETIME(3) NOT NULL, status TINYINT NOT NULL, amount_cents INT NOT NULL, note VARCHAR(100) NOT NULL" + v[1] + ")");
                Random random = new Random(round);
                long t0 = System.nanoTime();
                try (Connection c = conn(); PreparedStatement p = c.prepareStatement("INSERT INTO orders_w VALUES (?,?,?,?,?,?)")) {
                    c.setAutoCommit(false);
                    for (int i = 1; i <= 200_000; i++) {
                        p.setLong(1, i); p.setInt(2, 1 + random.nextInt(200)); p.setTimestamp(3, new Timestamp(1_767_225_600_000L + random.nextInt(1_000_000_000)));
                        p.setInt(4, random.nextInt(4)); p.setInt(5, random.nextInt(100_000)); p.setString(6, "note-" + random.nextInt(1_000_000));
                        p.addBatch();
                        if (i % 2000 == 0) { p.executeBatch(); c.commit(); }
                    }
                }
                times.add((System.nanoTime() - t0) / 1_000_000);
                exec("ANALYZE TABLE orders_w");
                size = query("SELECT CONCAT(ROUND(data_length/1048576,1), ' MB / ', ROUND(index_length/1048576,1), ' MB') FROM information_schema.tables WHERE table_schema='labs' AND table_name='orders_w'");
            }
            Collections.sort(times);
            System.out.printf("write.%s\t%s 个二级索引，写入 20 万行（每 2000 行一批）：3 轮中位数 %,dms；数据 / 二级索引 %s%n", v[0], v[0], times.get(1), size);
        }
    }

    // ---------- 工具 ----------

    static Connection conn() throws SQLException { return DriverManager.getConnection(URL, "root", "example_password"); }

    static void exec(String... sqls) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) { for (String sql : sqls) s.execute(sql); }
    }

    static long count(String sql) throws SQLException { return Long.parseLong(query(sql)); }

    static String query(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getString(1); }
    }

    static List<String> rows(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) out.add(r.getString(1)); }
        return out;
    }

    /** 在同一个连接上读取会话级 Handler_read_* 的增量，得到这条语句实际读取的行数。 */
    static long handlerReads(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            long before = sumReads(s);
            try (ResultSet r = s.executeQuery(sql)) { while (r.next()) { } }
            return sumReads(s) - before;
        }
    }

    static long sumReads(Statement s) throws SQLException {
        long total = 0;
        try (ResultSet r = s.executeQuery("SHOW SESSION STATUS LIKE 'Handler_read%'")) { while (r.next()) total += r.getLong(2); }
        return total;
    }

    /** 读取行数与 20 次平均耗时（微秒）。 */
    static long[] readAndTime(String sql) throws SQLException {
        long reads = handlerReads(sql);
        try (Connection c = conn(); Statement s = c.createStatement()) {
            for (int i = 0; i < 5; i++) try (ResultSet r = s.executeQuery(sql)) { while (r.next()) { } }
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) try (ResultSet r = s.executeQuery(sql)) { while (r.next()) { } }
            return new long[] {reads, (System.nanoTime() - t0) / 1000 / 20};
        }
    }

    static String explain(String sql) throws SQLException { return query("EXPLAIN ANALYZE " + sql); }
}
