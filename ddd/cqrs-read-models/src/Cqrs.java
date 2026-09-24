import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * 同一个运营看板（活动 1 各场次的确认数、候补数、已收金额）的四种读法，输出为「键<TAB>事实」：
 * 1. 经仓储加载每个场次聚合再汇总；2. 在命令侧表上 GROUP BY；3. 同一事务维护的读模型；4. 异步投影的读模型。
 */
public class Cqrs {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
    static final int SESSIONS = 200, PER_SESSION = 250, CAPACITY = 200;
    static Path out;

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        seed();
        String plans = "";
        String viaAggregates = timed("aggregate", Cqrs::viaAggregates);
        plans += explainBlock("group-by", GROUP_BY);
        String viaGroupBy = timed("group-by", () -> rows(GROUP_BY));
        plans += explainBlock("sync-read-model", READ_MODEL);
        String viaReadModel = timed("sync-read-model", () -> rows(READ_MODEL));
        Files.writeString(out.resolve("query-plans.txt"), plans);
        System.out.printf("same\t三种读法结果一致：聚合=%s，GROUP BY=%s，同步读模型=%s%n",
                digest(viaAggregates), digest(viaGroupBy), digest(viaReadModel));
        writeCost();
        asyncProjection();
    }

    static final String GROUP_BY = "SELECT s.id, SUM(r.status='CONFIRMED'), SUM(r.status='WAITLISTED'), SUM(r.paid_cents) "
            + "FROM session s JOIN registration r ON r.session_id = s.id WHERE s.event_id = 1 GROUP BY s.id ORDER BY s.id";
    static final String READ_MODEL = "SELECT session_id, confirmed, waitlisted, paid_cents FROM session_stats WHERE event_id = 1 ORDER BY session_id";
    static final String READ_MODEL_ASYNC = "SELECT session_id, confirmed, waitlisted, paid_cents FROM session_stats_async WHERE event_id = 1 ORDER BY session_id";

    // ---------- 初始数据：直接批量写入命令侧与事件表，再回填同步读模型 ----------

    static void seed() throws Exception {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("INSERT INTO session (id, event_id, capacity) VALUES (?,1,?)");
                 PreparedStatement r = c.prepareStatement("INSERT INTO registration (session_id, attendee_id, status, paid_cents) VALUES (?,?,?,?)");
                 PreparedStatement e = c.prepareStatement("INSERT INTO domain_event (session_id, type, amount_cents) VALUES (?,?,?)")) {
                Random random = new Random(7);
                for (int sid = 1; sid <= SESSIONS; sid++) {
                    s.setInt(1, sid); s.setInt(2, CAPACITY); s.addBatch();
                    for (int k = 0; k < PER_SESSION; k++) {
                        String status = k < CAPACITY ? "CONFIRMED" : "WAITLISTED";
                        int paid = status.equals("CONFIRMED") && random.nextInt(10) < 7 ? 9_900 : 0;
                        r.setInt(1, sid); r.setString(2, "a" + k); r.setString(3, status); r.setInt(4, paid); r.addBatch();
                        e.setInt(1, sid); e.setString(2, status); e.setInt(3, 0); e.addBatch();
                        if (paid > 0) { e.setInt(1, sid); e.setString(2, "PAID"); e.setInt(3, paid); e.addBatch(); }
                    }
                    if (sid % 20 == 0) { s.executeBatch(); r.executeBatch(); e.executeBatch(); c.commit(); }
                }
            }
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO session_stats SELECT s.id, s.event_id, SUM(r.status='CONFIRMED'), SUM(r.status='WAITLISTED'), SUM(r.paid_cents) "
                        + "FROM session s JOIN registration r ON r.session_id = s.id GROUP BY s.id, s.event_id");
                st.execute("ANALYZE TABLE session, registration, session_stats");
            }
            c.commit();
        }
        System.out.printf("seed\t%d 个场次，每个场次 %d 条报名（%d 确认、%d 候补），共 %d 条；事件 %d 条%n", SESSIONS, PER_SESSION, CAPACITY,
                PER_SESSION - CAPACITY, count("SELECT COUNT(*) FROM registration"), count("SELECT COUNT(*) FROM domain_event"));
    }

    // ---------- 读法一：经仓储加载聚合 ----------

    static final AtomicLong rowsRead = new AtomicLong();

    record Session(int id, int capacity, List<String[]> registrations) {}

    static Session load(Connection c, int id) throws SQLException {
        int capacity;
        try (PreparedStatement p = c.prepareStatement("SELECT capacity FROM session WHERE id=?")) { p.setInt(1, id); ResultSet r = p.executeQuery(); r.next(); capacity = r.getInt(1); rowsRead.incrementAndGet(); }
        List<String[]> regs = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT attendee_id, status, paid_cents FROM registration WHERE session_id=?")) {
            p.setInt(1, id); ResultSet r = p.executeQuery();
            while (r.next()) { regs.add(new String[] {r.getString(1), r.getString(2), r.getString(3)}); rowsRead.incrementAndGet(); }
        }
        return new Session(id, capacity, regs);
    }

    static String viaAggregates() throws SQLException {
        StringBuilder sb = new StringBuilder();
        rowsRead.set(0);
        try (Connection c = conn()) {
            List<Integer> ids = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id FROM session WHERE event_id=1 ORDER BY id")) { while (r.next()) ids.add(r.getInt(1)); }
            for (int id : ids) {
                Session s = load(c, id);
                long confirmed = s.registrations().stream().filter(x -> x[1].equals("CONFIRMED")).count();
                long waitlisted = s.registrations().size() - confirmed;
                long paid = s.registrations().stream().mapToLong(x -> Long.parseLong(x[2])).sum();
                sb.append(id).append(',').append(confirmed).append(',').append(waitlisted).append(',').append(paid).append('\n');
            }
        }
        return sb.toString();
    }

    // ---------- 写路径的代价：同步读模型多一条 UPDATE ----------

    static void register(Connection c, int sid, String attendee, boolean syncProjection) throws SQLException {
        c.setAutoCommit(false);
        int capacity, confirmed;
        try (PreparedStatement p = c.prepareStatement("SELECT capacity FROM session WHERE id=? FOR UPDATE")) { p.setInt(1, sid); ResultSet r = p.executeQuery(); r.next(); capacity = r.getInt(1); }
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM registration WHERE session_id=? AND status='CONFIRMED'")) { p.setInt(1, sid); ResultSet r = p.executeQuery(); r.next(); confirmed = r.getInt(1); }
        String status = confirmed < capacity ? "CONFIRMED" : "WAITLISTED";
        try (PreparedStatement p = c.prepareStatement("INSERT INTO registration (session_id, attendee_id, status) VALUES (?,?,?)")) { p.setInt(1, sid); p.setString(2, attendee); p.setString(3, status); p.executeUpdate(); }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO domain_event (session_id, type) VALUES (?,?)")) { p.setInt(1, sid); p.setString(2, status); p.executeUpdate(); }
        if (syncProjection) {
            try (PreparedStatement p = c.prepareStatement("UPDATE session_stats SET " + (status.equals("CONFIRMED") ? "confirmed=confirmed+1" : "waitlisted=waitlisted+1") + " WHERE session_id=?")) { p.setInt(1, sid); p.executeUpdate(); }
        }
        try (PreparedStatement p = c.prepareStatement("UPDATE session SET version=version+1 WHERE id=?")) { p.setInt(1, sid); p.executeUpdate(); }
        c.commit();
    }

    static void writeCost() throws Exception {
        for (int round = 1; round <= 3; round++) {
            for (boolean sync : List.of(false, true)) {
                try (Connection c = conn()) {
                    long t0 = System.nanoTime();
                    for (int i = 0; i < 1000; i++) register(c, 1 + i % SESSIONS, "w" + round + (sync ? "s" : "n") + i, sync);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("write.%s.%d\t顺序执行 1000 次报名命令：%dms%n", sync ? "with-projection" : "without-projection", round, ms);
                }
            }
        }
        // 没有同步维护的 3000 次写入要补进同步读模型，保持后面比较的前提一致
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE session_stats");
            s.execute("INSERT INTO session_stats SELECT s.id, s.event_id, SUM(r.status='CONFIRMED'), SUM(r.status='WAITLISTED'), SUM(r.paid_cents) "
                    + "FROM session s JOIN registration r ON r.session_id = s.id GROUP BY s.id, s.event_id");
        }
        System.out.printf("write.check\t回填后同步读模型与 GROUP BY 一致=%s%n", digest(rows(READ_MODEL)).equals(digest(rows(GROUP_BY))));
    }

    // ---------- 读法四：异步投影、暂停、追赶与重放 ----------

    static int project(int batch) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            long from;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT last_event_id FROM projection_checkpoint WHERE name='stats' FOR UPDATE")) { r.next(); from = r.getLong(1); }
            List<long[]> events = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT id, session_id, type, amount_cents FROM domain_event WHERE id > ? ORDER BY id LIMIT ?")) {
                p.setLong(1, from); p.setInt(2, batch); ResultSet r = p.executeQuery();
                while (r.next()) events.add(new long[] {r.getLong(1), r.getLong(2), switch (r.getString(3)) { case "CONFIRMED" -> 0; case "WAITLISTED" -> 1; default -> 2; }, r.getLong(4)});
            }
            if (events.isEmpty()) { c.commit(); return 0; }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO session_stats_async VALUES (?, 1, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                    + "confirmed=confirmed+VALUES(confirmed), waitlisted=waitlisted+VALUES(waitlisted), paid_cents=paid_cents+VALUES(paid_cents)")) {
                for (long[] e : events) {
                    p.setLong(1, e[1]); p.setLong(2, e[2] == 0 ? 1 : 0); p.setLong(3, e[2] == 1 ? 1 : 0); p.setLong(4, e[2] == 2 ? e[3] : 0); p.addBatch();
                }
                p.executeBatch();
            }
            try (PreparedStatement p = c.prepareStatement("UPDATE projection_checkpoint SET last_event_id=? WHERE name='stats'")) { p.setLong(1, events.getLast()[0]); p.executeUpdate(); }
            c.commit();
            return events.size();
        }
    }

    static void asyncProjection() throws Exception {
        long t0 = System.nanoTime();
        exec("INSERT INTO projection_checkpoint VALUES ('stats', 0)");
        int n, total = 0;
        while ((n = project(5000)) > 0) total += n;
        long rebuildMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("async.build\t从 0 重放 %d 个事件建立异步读模型：%dms；与 GROUP BY 一致=%s%n", total, rebuildMs, digest(rows(READ_MODEL_ASYNC)).equals(digest(rows(GROUP_BY))));

        // 暂停投影器，继续接收命令
        try (Connection c = conn()) { for (int i = 0; i < 500; i++) register(c, 1 + i % SESSIONS, "late" + i, true); }
        long lagEvents = count("SELECT COUNT(*) FROM domain_event WHERE id > (SELECT last_event_id FROM projection_checkpoint WHERE name='stats')");
        int staleRows = diffRows(rows(READ_MODEL_ASYNC), rows(GROUP_BY));
        System.out.printf("async.paused\t投影器暂停期间执行 500 次报名：积压 %d 个事件，异步读模型 %d/%d 个场次与写侧不一致；同步读模型一致=%s%n",
                lagEvents, staleRows, SESSIONS, digest(rows(READ_MODEL)).equals(digest(rows(GROUP_BY))));

        t0 = System.nanoTime();
        int caught = 0;
        while ((n = project(100)) > 0) caught += n;
        long catchUpMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("async.resumed\t恢复后追上 %d 个事件：%dms；与 GROUP BY 一致=%s%n", caught, catchUpMs, digest(rows(READ_MODEL_ASYNC)).equals(digest(rows(GROUP_BY))));

        // 读模型损坏或改了结构：清空后从事件重放
        exec("UPDATE session_stats_async SET confirmed = confirmed + 3 WHERE session_id <= 10");
        int corrupted = diffRows(rows(READ_MODEL_ASYNC), rows(GROUP_BY));
        exec("TRUNCATE TABLE session_stats_async", "UPDATE projection_checkpoint SET last_event_id=0");
        t0 = System.nanoTime();
        total = 0;
        while ((n = project(5000)) > 0) total += n;
        System.out.printf("async.replay\t人为改坏 %d 个场次后清空重放 %d 个事件：%dms；与 GROUP BY 一致=%s%n", corrupted, total,
                (System.nanoTime() - t0) / 1_000_000, digest(rows(READ_MODEL_ASYNC)).equals(digest(rows(GROUP_BY))));
    }

    // ---------- 工具 ----------

    interface Query { String run() throws Exception; }

    static String timed(String name, Query q) throws Exception {
        for (int i = 0; i < 3; i++) q.run();
        long rowsBefore = rowsRead.get();
        long t0 = System.nanoTime();
        String result = null;
        for (int i = 0; i < 10; i++) result = q.run();
        double ms = (System.nanoTime() - t0) / 1e6 / 10;
        String read = name.equals("aggregate") ? "，每次读取 " + rowsRead.get() + " 行" : "";
        System.out.printf("read.%s\t返回 %d 行，平均 %.1fms%s%n", name, result.split("\n").length, ms, read);
        return result;
    }

    static String rows(String sql) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) sb.append(r.getInt(1)).append(',').append(r.getLong(2)).append(',').append(r.getLong(3)).append(',').append(r.getLong(4)).append('\n');
        }
        return sb.toString();
    }

    static int diffRows(String a, String b) {
        List<String> x = List.of(a.split("\n")), y = List.of(b.split("\n"));
        int d = 0;
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) if (i >= x.size() || i >= y.size() || !x.get(i).equals(y.get(i))) d++;
        return d;
    }

    static String digest(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes());
        return HexFormat.of().formatHex(h).substring(0, 12);
    }

    static String explainBlock(String name, String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("EXPLAIN ANALYZE " + sql)) {
            r.next(); return "## " + name + "\n" + sql + "\n" + r.getString(1) + "\n";
        }
    }

    static Connection conn() throws SQLException { return DriverManager.getConnection(URL, "root", "example_password"); }

    static void exec(String... sqls) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) { for (String sql : sqls) s.execute(sql); }
    }

    static long count(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
}
