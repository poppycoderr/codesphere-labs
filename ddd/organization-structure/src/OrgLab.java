import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 组织结构的两层模型：知识层（允许哪类组织挂在哪类组织下、属于哪条结构线）与实例层（真实的组织与带有效期的上下级关系）。
 * 依次演示：按知识层校验、只改数据就新增规则、收紧规则后存量实例不再合法、同一组织在两条结构线上、按日期查上级、
 * 两个事务并发加边形成环（READ COMMITTED、SERIALIZABLE、锁住结构线三种做法）。两个事务的交错由闩锁固定。
 */
public class OrgLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static Connection pg() throws SQLException { return DriverManager.getConnection("jdbc:postgresql://postgres:5432/labs", "postgres", "example_password"); }
    static void exec(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }
    static String one(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { return r.next() ? r.getString(1) : null; } }
    static String err(Exception e) { return e.getClass().getSimpleName() + "：" + String.valueOf(e.getMessage()).split("\n")[0].replace("ERROR: ", ""); }

    /** 应用层的加边：先按知识层检查类型，再检查新上级是不是自己的下级（会不会成环），然后插入 */
    static void link(Connection c, String child, String parent, String structure, String from) throws SQLException {
        String allowed = one(c, "SELECT 1 FROM allowed_parent a JOIN org_unit ch ON ch.type = a.child_type JOIN org_unit pa ON pa.type = a.parent_type"
                + " WHERE ch.id = '" + child + "' AND pa.id = '" + parent + "' AND a.structure = '" + structure + "'");
        if (allowed == null) throw new IllegalStateException("知识层不允许 " + child + " 挂在 " + parent + " 下（" + structure + "）");
        String cycle = one(c, "WITH RECURSIVE up(id) AS (SELECT '" + parent + "'::text UNION SELECT l.parent FROM org_link l JOIN up ON l.child = up.id"
                + " WHERE l.structure = '" + structure + "') SELECT 1 FROM up WHERE id = '" + child + "'");
        if (cycle != null) throw new IllegalStateException("会形成环：" + parent + " 已经在 " + child + " 之下");
        exec(c, "INSERT INTO org_link (child, parent, structure, valid) VALUES ('" + child + "', '" + parent + "', '" + structure + "', daterange('" + from + "', NULL))");
    }
    static String tryLink(Connection c, String child, String parent, String structure, String from) {
        try { link(c, child, parent, structure, from); return "成功"; } catch (Exception e) { return "被拒绝（" + err(e) + "）"; }
    }

    interface Tx { void run(Connection c) throws Exception; }
    /** 两个事务都执行完检查（读）之后，才各自插入并提交 */
    static String race(int isolation, Tx before, String[][] edges) throws Exception {
        CountDownLatch bothChecked = new CountDownLatch(2);
        String[] result = new String[2];
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            final int id = t;
            ts[t] = new Thread(() -> {
                try (Connection c = pg()) {
                    c.setAutoCommit(false);
                    c.setTransactionIsolation(isolation);
                    before.run(c);
                    String[] e = edges[id];
                    String cycle = one(c, "WITH RECURSIVE up(id) AS (SELECT '" + e[1] + "'::text UNION SELECT l.parent FROM org_link l JOIN up ON l.child = up.id"
                            + " WHERE l.structure = 'LINE') SELECT 1 FROM up WHERE id = '" + e[0] + "'");
                    bothChecked.countDown();
                    bothChecked.await(3, TimeUnit.SECONDS);
                    if (cycle != null) throw new IllegalStateException("会形成环");
                    exec(c, "INSERT INTO org_link (child, parent, structure, valid) VALUES ('" + e[0] + "', '" + e[1] + "', 'LINE', daterange('2026-10-16', NULL))");
                    c.commit();
                    result[id] = "提交成功";
                } catch (Exception e) { result[id] = "失败（" + err(e) + "）"; }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        long ok = java.util.Arrays.stream(result).filter(x -> x.equals("提交成功")).count();
        String fail = java.util.Arrays.stream(result).filter(x -> !x.equals("提交成功")).findFirst().orElse("");
        return "提交成功 " + ok + " 个" + (ok < 2 ? "，另一个" + fail : "");
    }

    static void resetCycleCase(Connection p) throws SQLException {
        exec(p, "DELETE FROM org_link WHERE child IN ('D1', 'D2')");
    }
    static String cycles(Connection p) throws SQLException {
        return one(p, "WITH RECURSIVE walk(start, id, depth) AS (SELECT child, parent, 1 FROM org_link WHERE structure = 'LINE'"
                + " UNION ALL SELECT w.start, l.parent, w.depth + 1 FROM walk w JOIN org_link l ON l.child = w.id AND l.structure = 'LINE' WHERE w.depth < 10)"
                + " SELECT COUNT(DISTINCT start) FROM walk WHERE start = id");
    }

    public static void main(String[] args) throws Exception {
        try (Connection p = pg()) {
            out("env", "java.version=" + System.getProperty("java.version") + " postgres=" + p.getMetaData().getDatabaseProductVersion().split(" ")[0]);
            exec(p, "CREATE EXTENSION IF NOT EXISTS btree_gist");
            exec(p, "DROP TABLE IF EXISTS org_link, org_unit, allowed_parent, structure CASCADE");
            // 知识层：结构线与允许的上下级类型
            exec(p, "CREATE TABLE structure (code TEXT PRIMARY KEY)");
            exec(p, "INSERT INTO structure VALUES ('LINE'), ('FUNCTION')");
            exec(p, "CREATE TABLE allowed_parent (child_type TEXT, parent_type TEXT, structure TEXT REFERENCES structure, PRIMARY KEY (child_type, parent_type, structure))");
            exec(p, "INSERT INTO allowed_parent VALUES ('DIVISION','COMPANY','LINE'), ('DEPARTMENT','DIVISION','LINE'), ('DEPARTMENT','DEPARTMENT','LINE'), ('TEAM','DEPARTMENT','LINE'), ('TEAM','DEPARTMENT','FUNCTION')");
            // 实例层：组织与带有效期的上下级关系；同一结构线上，一个组织在任一天最多有一个上级
            exec(p, "CREATE TABLE org_unit (id TEXT PRIMARY KEY, type TEXT NOT NULL)");
            exec(p, "INSERT INTO org_unit VALUES ('HQ','COMPANY'), ('DIV-R','DIVISION'), ('DEPT-A','DEPARTMENT'), ('DEPT-B','DEPARTMENT'), ('DEPT-C','DEPARTMENT'), ('TEAM-X','TEAM'), ('TEAM-Y','TEAM'), ('D1','DEPARTMENT'), ('D2','DEPARTMENT')");
            exec(p, "CREATE TABLE org_link (child TEXT REFERENCES org_unit, parent TEXT REFERENCES org_unit, structure TEXT REFERENCES structure, valid DATERANGE NOT NULL,"
                    + " EXCLUDE USING gist (child WITH =, structure WITH =, valid WITH &&))");

            link(p, "DIV-R", "HQ", "LINE", "2026-01-01");
            link(p, "DEPT-A", "DIV-R", "LINE", "2026-01-01");
            link(p, "DEPT-C", "DIV-R", "LINE", "2026-01-01");
            out("knowledge.allowed", "TEAM-X 挂在 DEPT-A 下（行政线）：" + tryLink(p, "TEAM-X", "DEPT-A", "LINE", "2026-01-01"));
            out("knowledge.rejected", "DEPT-B 挂在 TEAM-X 下（行政线）：" + tryLink(p, "DEPT-B", "TEAM-X", "LINE", "2026-01-01"));
            out("knowledge.new_rule_before", "TEAM-Y 直接挂在 DIV-R 下：" + tryLink(p, "TEAM-Y", "DIV-R", "LINE", "2026-01-01"));
            exec(p, "INSERT INTO allowed_parent VALUES ('TEAM','DIVISION','LINE')");
            out("knowledge.new_rule_after", "在知识层加一行 (TEAM, DIVISION, LINE)，不改代码，再挂一次：" + tryLink(p, "TEAM-Y", "DIV-R", "LINE", "2026-01-01"));
            exec(p, "DELETE FROM allowed_parent WHERE child_type = 'TEAM' AND parent_type = 'DIVISION'");
            out("knowledge.tightened", "随后删掉这条规则：新的挂接被拒绝，但已经存在、按当前规则不合法的关系有 "
                    + one(p, "SELECT COUNT(*) FROM org_link l JOIN org_unit c ON c.id = l.child JOIN org_unit pa ON pa.id = l.parent"
                    + " WHERE NOT EXISTS (SELECT 1 FROM allowed_parent a WHERE a.child_type = c.type AND a.parent_type = pa.type AND a.structure = l.structure)")
                    + " 条（TEAM-Y → DIV-R）：改知识层是一次数据迁移");

            out("structures.two_lines", "TEAM-X 在职能线上挂到 DEPT-B：" + tryLink(p, "TEAM-X", "DEPT-B", "FUNCTION", "2026-01-01") + "；TEAM-X 的上级："
                    + one(p, "SELECT string_agg(structure || '=' || parent, ', ' ORDER BY structure) FROM org_link WHERE child = 'TEAM-X'"));

            // 有效期：7 月 1 日起 TEAM-X 从 DEPT-A 调到 DEPT-C
            out("validity.overlap", "不结束旧关系，直接新增 TEAM-X → DEPT-C（7 月 1 日起）：" + tryLink(p, "TEAM-X", "DEPT-C", "LINE", "2026-07-01"));
            exec(p, "UPDATE org_link SET valid = daterange(lower(valid), '2026-07-01') WHERE child = 'TEAM-X' AND structure = 'LINE'");
            out("validity.move", "先把旧关系截止到 7 月 1 日再新增：" + tryLink(p, "TEAM-X", "DEPT-C", "LINE", "2026-07-01"));
            out("validity.as_of", "TEAM-X 在行政线上的上级：6 月 15 日 " + one(p, "SELECT parent FROM org_link WHERE child = 'TEAM-X' AND structure = 'LINE' AND valid @> DATE '2026-06-15'")
                    + "，8 月 1 日 " + one(p, "SELECT parent FROM org_link WHERE child = 'TEAM-X' AND structure = 'LINE' AND valid @> DATE '2026-08-01'"));

            // 无环：部门可以挂在部门下，于是两个事务可能分别加 D1 → D2 和 D2 → D1
            String[][] edges = { { "D1", "D2" }, { "D2", "D1" } };
            out("cycle.sequential", "顺序执行：D1 挂到 D2 下 " + tryLink(p, "D1", "D2", "LINE", "2026-10-16") + "；D2 再挂到 D1 下 " + tryLink(p, "D2", "D1", "LINE", "2026-10-16"));
            resetCycleCase(p);
            out("cycle.read_committed", "两个事务并发加 D1 → D2 与 D2 → D1，各自先查再插（READ COMMITTED）：" + race(Connection.TRANSACTION_READ_COMMITTED, c -> { }, edges) + "；行政线上处在环里的组织 " + cycles(p) + " 个");
            resetCycleCase(p);
            out("cycle.serializable", "同样的两个事务用 SERIALIZABLE：" + race(Connection.TRANSACTION_SERIALIZABLE, c -> { }, edges) + "；处在环里的组织 " + cycles(p) + " 个");
            resetCycleCase(p);
            out("cycle.lock_structure", "检查前先 SELECT … FOR UPDATE 锁住结构线 LINE（READ COMMITTED）：" + race(Connection.TRANSACTION_READ_COMMITTED,
                    c -> one(c, "SELECT code FROM structure WHERE code = 'LINE' FOR UPDATE"), edges) + "；处在环里的组织 " + cycles(p) + " 个");
        }
    }
}
