import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * 领域模型怎样落到数据库，输出为「键<TAB>事实」，执行计划写入参数给出的目录：
 * 1. 场次聚合保存—重建往返；2. 重建不是创建；3. 版本号冲突；4. 子表的两种保存方式；5. 票种继承的三种映射。
 */
public class Persistence {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
    static Path out;

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        roundTrip();
        restoreIsNotCreate();
        versionConflict();
        childRowWrites();
        inheritance();
    }

    // ---------- 领域模型（纯 Java，不认识表） ----------

    record Attendee(String id, String phone) {}

    static final class Session {
        static int minimumCapacity = 1;
        final String id; final int capacity; final long version;
        final List<Attendee> confirmed = new ArrayList<>(), waitlist = new ArrayList<>();
        final List<String> pendingEvents = new ArrayList<>();

        private Session(String id, int capacity, long version) { this.id = id; this.capacity = capacity; this.version = version; }

        static Session open(String id, int capacity) {
            if (capacity < minimumCapacity) throw new IllegalArgumentException("容量至少为 " + minimumCapacity);
            return new Session(id, capacity, 0);
        }

        /** 重建：恢复已经发生过的状态，不执行创建规则，不登记事件。 */
        static Session restore(String id, int capacity, long version, List<Attendee> confirmed, List<Attendee> waitlist) {
            Session s = new Session(id, capacity, version);
            s.confirmed.addAll(confirmed); s.waitlist.addAll(waitlist);
            return s;
        }

        void register(Attendee a) {
            if (confirmed.contains(a) || waitlist.contains(a)) throw new IllegalStateException("DUPLICATE");
            if (confirmed.size() < capacity) { confirmed.add(a); pendingEvents.add("RegistrationConfirmed(" + a.id() + ")"); }
            else { waitlist.add(a); pendingEvents.add("CandidateWaitlisted(" + a.id() + ")"); }
        }

        String snapshot() { return "capacity=" + capacity + " version=" + version + " confirmed=" + ids(confirmed) + " waitlist=" + ids(waitlist); }
        static List<String> ids(List<Attendee> l) { return l.stream().map(Attendee::id).toList(); }
    }

    // ---------- 仓储实现：显式映射 ----------

    static final class ConcurrentModification extends RuntimeException { ConcurrentModification() { super("版本已变化"); } }

    static Session load(Connection c, String id) throws SQLException {
        int capacity; long version;
        try (PreparedStatement p = c.prepareStatement("SELECT capacity, version FROM session WHERE id=?")) {
            p.setString(1, id); ResultSet r = p.executeQuery(); r.next(); capacity = r.getInt(1); version = r.getLong(2);
        }
        List<Attendee> confirmed = new ArrayList<>(), waitlist = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT attendee_id, phone, status FROM session_attendee WHERE session_id=? ORDER BY status, position")) {
            p.setString(1, id); ResultSet r = p.executeQuery();
            while (r.next()) (r.getString(3).equals("CONFIRMED") ? confirmed : waitlist).add(new Attendee(r.getString(1), r.getString(2)));
        }
        return Session.restore(id, capacity, version, confirmed, waitlist);
    }

    /** 按差异保存子表：只插入新增的参会人。返回写入的行数（根 + 子表）。 */
    static int saveDiff(Connection c, Session s, Set<String> loadedIds) throws SQLException {
        int rows;
        try (PreparedStatement p = c.prepareStatement("UPDATE session SET version=version+1 WHERE id=? AND version=?")) {
            p.setString(1, s.id); p.setLong(2, s.version); rows = p.executeUpdate();
            if (rows == 0) throw new ConcurrentModification();
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO session_attendee VALUES (?,?,?,?,?)")) {
            rows += insertMissing(p, s, s.confirmed, "CONFIRMED", loadedIds) + insertMissing(p, s, s.waitlist, "WAITLISTED", loadedIds);
        }
        return rows;
    }

    static int insertMissing(PreparedStatement p, Session s, List<Attendee> list, String status, Set<String> loadedIds) throws SQLException {
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            if (loadedIds.contains(list.get(i).id())) continue;
            p.setString(1, s.id); p.setString(2, list.get(i).id()); p.setString(3, list.get(i).phone()); p.setString(4, status); p.setInt(5, i + 1);
            n += p.executeUpdate();
        }
        return n;
    }

    /** 整体替换子表：先删后插，实现简单，但每次都重写全部子行。 */
    static int saveReplaceAll(Connection c, Session s) throws SQLException {
        int rows;
        try (PreparedStatement p = c.prepareStatement("UPDATE session SET version=version+1 WHERE id=? AND version=?")) {
            p.setString(1, s.id); p.setLong(2, s.version); rows = p.executeUpdate();
            if (rows == 0) throw new ConcurrentModification();
        }
        try (PreparedStatement p = c.prepareStatement("DELETE FROM session_attendee WHERE session_id=?")) { p.setString(1, s.id); rows += p.executeUpdate(); }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO session_attendee VALUES (?,?,?,?,?)")) {
            rows += insertMissing(p, s, s.confirmed, "CONFIRMED", Set.of()) + insertMissing(p, s, s.waitlist, "WAITLISTED", Set.of());
        }
        return rows;
    }

    static void insertNew(Connection c, Session s) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO session VALUES (?,?,0)")) { p.setString(1, s.id); p.setInt(2, s.capacity); p.executeUpdate(); }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO session_attendee VALUES (?,?,?,?,?)")) {
            insertMissing(p, s, s.confirmed, "CONFIRMED", Set.of()); insertMissing(p, s, s.waitlist, "WAITLISTED", Set.of());
        }
    }

    static Set<String> idsOf(Session s) {
        Set<String> ids = new HashSet<>(Session.ids(s.confirmed)); ids.addAll(Session.ids(s.waitlist)); return ids;
    }

    // ---------- 1. 往返 ----------

    static void roundTrip() throws Exception {
        exec("TRUNCATE TABLE session", "TRUNCATE TABLE session_attendee");
        Session s = Session.open("S-1", 5);
        for (int i = 1; i <= 7; i++) s.register(new Attendee("a" + i, "1380013800" + i));
        String before = s.snapshot().replace("version=0", "version=?");
        try (Connection c = conn()) { insertNew(c, s); }
        Session loaded;
        try (Connection c = conn()) { loaded = load(c, "S-1"); }
        String after = loaded.snapshot().replace("version=0", "version=?");
        System.out.printf("roundtrip\t保存前 %s；新连接重建后 %s；一致=%s；重建后待发布事件 %d 个（保存前 %d 个）%n",
                before, after, before.equals(after), loaded.pendingEvents.size(), s.pendingEvents.size());
    }

    // ---------- 2. 重建不是创建 ----------

    static void restoreIsNotCreate() throws Exception {
        Session.minimumCapacity = 10; // 上线了新规则：新建场次容量至少 10
        String viaOpen;
        try { Session.open("S-1", 5); viaOpen = "成功"; } catch (IllegalArgumentException e) { viaOpen = "抛出「" + e.getMessage() + "」"; }
        Session restored;
        try (Connection c = conn()) { restored = load(c, "S-1"); }
        Session.minimumCapacity = 1;
        Session replayed = Session.open("S-1", 5);
        for (Attendee a : restored.confirmed) replayed.register(a);
        for (Attendee a : restored.waitlist) replayed.register(a);
        System.out.printf("restore\t新规则「容量至少 10」上线后：历史场次（容量 5）经 restore 重建成功，待发布事件 %d 个；经 open 创建%s；放宽规则后按 register 重放，登记事件 %d 个%n",
                restored.pendingEvents.size(), viaOpen, replayed.pendingEvents.size());
    }

    // ---------- 3. 版本号冲突 ----------

    static void versionConflict() throws Exception {
        try (Connection a = conn(); Connection b = conn()) {
            a.setAutoCommit(false); b.setAutoCommit(false);
            Session sa = load(a, "S-1"), sb = load(b, "S-1");
            Set<String> la = idsOf(sa), lb = idsOf(sb);
            sa.register(new Attendee("x", "13900139001"));
            sb.register(new Attendee("y", "13900139002"));
            int rowsA = saveDiff(a, sa, la); a.commit();
            String resultB;
            try { saveDiff(b, sb, lb); b.commit(); resultB = "提交"; }
            catch (ConcurrentModification e) { b.rollback(); resultB = "ConcurrentModification，回滚"; }
            Session fin;
            try (Connection c = conn()) { fin = load(c, "S-1"); }
            System.out.printf("version\t两个请求都从版本 %d 加载：A 写入 %d 行并提交；B %s；最终版本 %d，候补 %s%n",
                    sa.version, rowsA, resultB, fin.version, Session.ids(fin.waitlist));
        }
    }

    // ---------- 4. 子表的保存方式 ----------

    static void childRowWrites() throws Exception {
        exec("TRUNCATE TABLE session", "TRUNCATE TABLE session_attendee");
        Session big = Session.open("S-BIG", 2000);
        for (int i = 0; i < 1000; i++) big.register(new Attendee("p" + i, String.format("137%08d", i)));
        try (Connection c = conn()) { insertNew(c, big); }
        for (String mode : List.of("diff", "replace-all")) {
            try (Connection c = conn()) {
                c.setAutoCommit(false);
                Session s = load(c, "S-BIG");
                Set<String> loaded = idsOf(s);
                s.register(new Attendee("new-" + mode, "13600136000"));
                long t0 = System.nanoTime();
                int rows = mode.equals("diff") ? saveDiff(c, s, loaded) : saveReplaceAll(c, s);
                c.commit();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("children.%s\t已有 1000 个参会人的场次新增 1 人：写入 %d 行，%dms%n", mode, rows, ms);
            }
        }
    }

    // ---------- 5. 继承映射 ----------

    static final int SESSIONS = 300, PER_TYPE = 100;

    static void inheritance() throws Exception {
        long id = 0;
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try (PreparedStatement st = c.prepareStatement("INSERT INTO st_ticket VALUES (?,?,?,?,?,?)");
                 PreparedStatement cf = c.prepareStatement("INSERT INTO ct_free_ticket VALUES (?,?,?)");
                 PreparedStatement cp = c.prepareStatement("INSERT INTO ct_paid_ticket VALUES (?,?,?,?)");
                 PreparedStatement cg = c.prepareStatement("INSERT INTO ct_group_ticket VALUES (?,?,?,?,?)");
                 PreparedStatement jt = c.prepareStatement("INSERT INTO jt_ticket VALUES (?,?,?,?)");
                 PreparedStatement jp = c.prepareStatement("INSERT INTO jt_paid_detail VALUES (?,?)");
                 PreparedStatement jg = c.prepareStatement("INSERT INTO jt_group_detail VALUES (?,?,?)")) {
                Random random = new Random(42);
                for (int s = 1; s <= SESSIONS; s++) {
                    for (int k = 0; k < PER_TYPE; k++) {
                        for (String type : List.of("FREE", "PAID", "GROUP")) {
                            id++;
                            Integer price = type.equals("FREE") ? null : 1000 + random.nextInt(49_000);
                            Integer min = type.equals("GROUP") ? 3 + random.nextInt(8) : null;
                            String name = type.toLowerCase() + "-" + k;
                            st.setLong(1, id); st.setInt(2, s); st.setString(3, type); st.setString(4, name); st.setObject(5, price); st.setObject(6, min); st.addBatch();
                            jt.setLong(1, id); jt.setInt(2, s); jt.setString(3, type); jt.setString(4, name); jt.addBatch();
                            switch (type) {
                                case "FREE" -> { cf.setLong(1, id); cf.setInt(2, s); cf.setString(3, name); cf.addBatch(); }
                                case "PAID" -> { cp.setLong(1, id); cp.setInt(2, s); cp.setString(3, name); cp.setInt(4, price); cp.addBatch();
                                                 jp.setLong(1, id); jp.setInt(2, price); jp.addBatch(); }
                                default -> { cg.setLong(1, id); cg.setInt(2, s); cg.setString(3, name); cg.setInt(4, price); cg.setInt(5, min); cg.addBatch();
                                             jg.setLong(1, id); jg.setInt(2, price); jg.setInt(3, min); jg.addBatch(); }
                            }
                        }
                    }
                    if (s % 50 == 0) {
                        for (PreparedStatement p : List.of(st, cf, cp, cg, jt)) p.executeBatch();
                        jp.executeBatch(); jg.executeBatch(); c.commit();
                    }
                }
            }
        }
        exec("ANALYZE TABLE st_ticket, ct_free_ticket, ct_paid_ticket, ct_group_ticket, jt_ticket, jt_paid_detail, jt_group_detail");
        System.out.printf("inherit.rows\t%d 个场次、每种票 %d 张，共 %d 张票%n", SESSIONS, PER_TYPE, id);

        Map<String, String[]> queries = new LinkedHashMap<>();
        queries.put("single-table", new String[] {
                "SELECT id, type, name, price_cents, min_size FROM st_ticket WHERE session_id = 42",
                "SELECT id, name, price_cents FROM st_ticket WHERE type = 'PAID' AND price_cents > 45000"});
        queries.put("concrete-tables", new String[] {
                "SELECT id, 'FREE' AS type, name, NULL AS price_cents, NULL AS min_size FROM ct_free_ticket WHERE session_id = 42 UNION ALL "
                        + "SELECT id, 'PAID', name, price_cents, NULL FROM ct_paid_ticket WHERE session_id = 42 UNION ALL "
                        + "SELECT id, 'GROUP', name, price_cents, min_size FROM ct_group_ticket WHERE session_id = 42",
                "SELECT id, name, price_cents FROM ct_paid_ticket WHERE price_cents > 45000"});
        queries.put("joined", new String[] {
                "SELECT t.id, t.type, t.name, COALESCE(p.price_cents, g.price_cents) AS price_cents, g.min_size FROM jt_ticket t "
                        + "LEFT JOIN jt_paid_detail p ON p.ticket_id = t.id LEFT JOIN jt_group_detail g ON g.ticket_id = t.id WHERE t.session_id = 42",
                "SELECT t.id, t.name, p.price_cents FROM jt_paid_detail p JOIN jt_ticket t ON t.id = p.ticket_id WHERE p.price_cents > 45000"});
        StringBuilder plans = new StringBuilder();
        for (var e : queries.entrySet()) {
            String[] q = e.getValue();
            int[] counts = {count("SELECT COUNT(*) FROM (" + q[0] + ") x"), count("SELECT COUNT(*) FROM (" + q[1] + ") x")};
            double[] avg = {avgMillis(q[0]), avgMillis(q[1])};
            for (int i = 0; i < 2; i++) plans.append("## ").append(e.getKey()).append(i == 0 ? " 场次 42 的全部票种" : " 价格高于 450 元的付费票").append("\n").append(q[i]).append("\n").append(explain(q[i])).append("\n");
            System.out.printf("inherit.%s\t场次 42 的全部票种 %d 行，平均 %.2fms；价格高于 450 元的付费票 %d 行，平均 %.2fms%n", e.getKey(), counts[0], avg[0], counts[1], avg[1]);
        }
        Files.writeString(out.resolve("inheritance-plans.txt"), plans.toString());

        String sizes = String.join("；", List.of(
                "single-table " + size("st_ticket"),
                "concrete-tables " + size("ct_free_ticket", "ct_paid_ticket", "ct_group_ticket"),
                "joined " + size("jt_ticket", "jt_paid_detail", "jt_group_detail")));
        System.out.printf("inherit.size\t数据 + 索引：%s%n", sizes);

        System.out.printf("inherit.constraint.single-table\t插入没有价格的付费票：%s%n", tryExec("INSERT INTO st_ticket VALUES (900001, 1, 'PAID', 'bad', NULL, NULL)"));
        System.out.printf("inherit.constraint.concrete-tables\t在免费票表和付费票表各插入 id=900002：%s%n",
                tryExec("INSERT INTO ct_free_ticket VALUES (900002, 1, 'dup')") + " / " + tryExec("INSERT INTO ct_paid_ticket VALUES (900002, 1, 'dup', 100)"));
        System.out.printf("inherit.constraint.joined\t只插入父表 type='PAID' 而不插入价格子表：%s%n", tryExec("INSERT INTO jt_ticket VALUES (900003, 1, 'PAID', 'orphan')"));
    }

    // ---------- 工具 ----------

    static Connection conn() throws SQLException { return DriverManager.getConnection(URL, "root", "example_password"); }

    static void exec(String... sqls) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) { for (String sql : sqls) s.execute(sql); }
    }

    static String tryExec(String sql) {
        try { exec(sql); return "成功"; } catch (SQLException e) { return "失败（" + e.getMessage() + "）"; }
    }

    static int count(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getInt(1); }
    }

    static double avgMillis(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            for (int i = 0; i < 20; i++) drain(s.executeQuery(sql));
            long t0 = System.nanoTime();
            for (int i = 0; i < 100; i++) drain(s.executeQuery(sql));
            return (System.nanoTime() - t0) / 1e6 / 100;
        }
    }

    static void drain(ResultSet r) throws SQLException { while (r.next()) { } r.close(); }

    static String explain(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("EXPLAIN ANALYZE " + sql)) {
            r.next(); return r.getString(1);
        }
    }

    static String size(String... tables) throws SQLException {
        String in = "'" + String.join("','", tables) + "'";
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT ROUND(SUM(data_length + index_length) / 1048576, 1) FROM information_schema.tables WHERE table_schema = 'labs' AND table_name IN (" + in + ")")) {
            r.next(); return r.getString(1) + " MB";
        }
    }
}
