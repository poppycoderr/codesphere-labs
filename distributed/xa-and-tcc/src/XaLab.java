import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 两个资源管理器（同一 MySQL 实例里的 bank_a、bank_b 两个库，各用一个连接）之间的两阶段提交，以及 TCC 的空回滚、悬挂与重复确认。
 * 参数 phase1：正常提交、准备后协调者消失、只提交了一个分支；phase2：实例重启之后的恢复；tcc：TCC 的三个问题。
 */
public class XaLab {
    static void out(String k, Object v) { System.out.println(k + "\t" + v); }
    static Connection conn() throws SQLException { return DriverManager.getConnection("jdbc:mysql://mysql:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "example_password"); }
    static void exec(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }
    static String one(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { return r.next() ? r.getString(1) : null; } }
    static String tryExec(Connection c, String sql) { try { exec(c, sql); return "成功"; } catch (SQLException e) { return "失败（" + e.getMessage().split("\n")[0] + "）"; } }
    static String balances(Connection c) throws SQLException { return "a=" + one(c, "SELECT balance FROM bank_a.account WHERE id = 1") + " b=" + one(c, "SELECT balance FROM bank_b.account WHERE id = 1"); }
    static List<String> recover(Connection c) throws SQLException {
        List<String> x = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("XA RECOVER")) { while (r.next()) x.add(r.getString("data")); }
        x.sort(null); return x;
    }
    static void branch(Connection c, String xid, String sql, boolean prepare) throws SQLException {
        exec(c, "XA START " + xid); exec(c, sql); exec(c, "XA END " + xid); if (prepare) exec(c, "XA PREPARE " + xid);
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "phase1" -> phase1();
            case "phase2" -> phase2();
            case "tcc" -> tcc();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static void phase1() throws Exception {
        try (Connection admin = conn()) {
            out("env", "java.version=" + System.getProperty("java.version") + " mysql=" + admin.getMetaData().getDatabaseProductVersion());
            for (String db : new String[] { "bank_a", "bank_b" }) {
                exec(admin, "DROP DATABASE IF EXISTS " + db); exec(admin, "CREATE DATABASE " + db);
                exec(admin, "CREATE TABLE " + db + ".account (id INT PRIMARY KEY, balance INT)");
                exec(admin, "INSERT INTO " + db + ".account VALUES (1, 1000), (2, 1000)");
            }
            // 一、正常的两阶段提交
            try (Connection a = conn(); Connection b = conn()) {
                branch(a, "'t1','a'", "UPDATE bank_a.account SET balance = balance - 100 WHERE id = 1", true);
                branch(b, "'t1','b'", "UPDATE bank_b.account SET balance = balance + 100 WHERE id = 1", true);
                out("xa.normal.prepared", "两个分支都 PREPARE 之后，XA RECOVER 列出 " + recover(admin));
                exec(a, "XA COMMIT 't1','a'"); exec(b, "XA COMMIT 't1','b'");
                out("xa.normal.committed", "两个分支都 COMMIT：" + balances(admin));
            }
            // 二、准备之后协调者消失（两个连接都关闭）
            Connection a = conn(), b = conn();
            branch(a, "'t2','a'", "UPDATE bank_a.account SET balance = balance - 200 WHERE id = 1", true);
            branch(b, "'t2','b'", "UPDATE bank_b.account SET balance = balance + 200 WHERE id = 1", true);
            a.close(); b.close();
            out("xa.orphan.recover", "准备之后协调者的连接全部断开：XA RECOVER 仍列出 " + recover(admin) + "；余额仍是 " + balances(admin));
            try (Connection other = conn()) {
                out("xa.orphan.lock", "另一个事务更新 bank_a 的同一行（锁等待超时 2 秒）：" + tryExec(other, "UPDATE bank_a.account SET balance = 0 WHERE id = 1"));
                out("xa.orphan.other_row", "更新同一张表的另一行：" + tryExec(other, "UPDATE bank_a.account SET balance = balance + 0 WHERE id = 2"));
            }
            // 三、协调者只提交了一个分支就消失
            Connection a3 = conn(), b3 = conn();
            branch(a3, "'t3','a'", "UPDATE bank_a.account SET balance = balance - 300 WHERE id = 2", true);
            branch(b3, "'t3','b'", "UPDATE bank_b.account SET balance = balance + 300 WHERE id = 2", true);
            exec(a3, "XA COMMIT 't3','a'");
            a3.close(); b3.close();
            out("xa.partial", "协调者提交了分支 a 后消失：bank_a.id=2 已是 " + one(admin, "SELECT balance FROM bank_a.account WHERE id = 2") + "，XA RECOVER 列出 " + recover(admin) + "；只看数据库无法知道 t3 该提交还是回滚，需要协调者自己记录的决定");
        }
    }

    static void phase2() throws Exception {
        try (Connection admin = conn()) {
            out("restart.recover", "MySQL 实例重启之后 XA RECOVER：" + recover(admin));
            try (Connection other = conn()) {
                out("restart.lock", "重启之后，另一个事务更新 bank_a.id=1：" + tryExec(other, "UPDATE bank_a.account SET balance = 0 WHERE id = 1"));
            }
            exec(admin, "XA COMMIT 't2','a'"); exec(admin, "XA COMMIT 't2','b'");
            exec(admin, "XA COMMIT 't3','b'");
            out("restart.resolve", "由恢复程序按协调者的决定从新会话 XA COMMIT 剩余分支：XA RECOVER " + recover(admin) + "；id=1 " + balances(admin)
                    + "；id=2 a=" + one(admin, "SELECT balance FROM bank_a.account WHERE id = 2") + " b=" + one(admin, "SELECT balance FROM bank_b.account WHERE id = 2"));
        }
    }

    // ---------------- TCC ----------------
    interface Tcc { void tryReserve(Connection c, String xid) throws SQLException; void confirm(Connection c, String xid) throws SQLException; void cancel(Connection c, String xid) throws SQLException; }
    /** 朴素实现：每一步只改余额与冻结金额 */
    static final Tcc NAIVE = new Tcc() {
        public void tryReserve(Connection c, String x) throws SQLException { exec(c, "UPDATE tcc.account SET balance = balance - 100, frozen = frozen + 100 WHERE id = 1"); }
        public void confirm(Connection c, String x) throws SQLException { exec(c, "UPDATE tcc.account SET frozen = frozen - 100 WHERE id = 1"); }
        public void cancel(Connection c, String x) throws SQLException { exec(c, "UPDATE tcc.account SET balance = balance + 100, frozen = frozen - 100 WHERE id = 1"); }
    };
    /** 带分支状态表：每个全局事务在本服务里只有一条记录，状态 TRIED / CONFIRMED / CANCELLED */
    static final Tcc GUARDED = new Tcc() {
        String state(Connection c, String x) throws SQLException { return one(c, "SELECT state FROM tcc.branch WHERE xid = '" + x + "' FOR UPDATE"); }
        public void tryReserve(Connection c, String x) throws SQLException {
            c.setAutoCommit(false);
            try {
                if (state(c, x) != null) { c.commit(); return; }                       // 已取消（防悬挂）或已执行过（幂等）
                exec(c, "INSERT INTO tcc.branch VALUES ('" + x + "', 'TRIED')");
                exec(c, "UPDATE tcc.account SET balance = balance - 100, frozen = frozen + 100 WHERE id = 1");
                c.commit();
            } finally { c.setAutoCommit(true); }
        }
        public void confirm(Connection c, String x) throws SQLException {
            c.setAutoCommit(false);
            try {
                if ("TRIED".equals(state(c, x))) { exec(c, "UPDATE tcc.branch SET state = 'CONFIRMED' WHERE xid = '" + x + "'"); exec(c, "UPDATE tcc.account SET frozen = frozen - 100 WHERE id = 1"); }
                c.commit();
            } finally { c.setAutoCommit(true); }
        }
        public void cancel(Connection c, String x) throws SQLException {
            c.setAutoCommit(false);
            try {
                String s = state(c, x);
                if (s == null) exec(c, "INSERT INTO tcc.branch VALUES ('" + x + "', 'CANCELLED')");   // 空回滚：只留下标记
                else if (s.equals("TRIED")) { exec(c, "UPDATE tcc.branch SET state = 'CANCELLED' WHERE xid = '" + x + "'"); exec(c, "UPDATE tcc.account SET balance = balance + 100, frozen = frozen - 100 WHERE id = 1"); }
                c.commit();
            } finally { c.setAutoCommit(true); }
        }
    };
    /** 只处理了空回滚：没有 Try 记录时 Cancel 什么也不做，但也不留下标记 */
    static final Tcc EMPTY_ONLY = new Tcc() {
        public void tryReserve(Connection c, String x) throws SQLException { GUARDED.tryReserve(c, x); }
        public void confirm(Connection c, String x) throws SQLException { GUARDED.confirm(c, x); }
        public void cancel(Connection c, String x) throws SQLException {
            if ("TRIED".equals(one(c, "SELECT state FROM tcc.branch WHERE xid = '" + x + "'"))) GUARDED.cancel(c, x);
        }
    };
    static void reset(Connection c) throws SQLException {
        exec(c, "DROP DATABASE IF EXISTS tcc"); exec(c, "CREATE DATABASE tcc");
        exec(c, "CREATE TABLE tcc.account (id INT PRIMARY KEY, balance INT, frozen INT)"); exec(c, "INSERT INTO tcc.account VALUES (1, 1000, 0)");
        exec(c, "CREATE TABLE tcc.branch (xid VARCHAR(64) PRIMARY KEY, state VARCHAR(16))");
    }
    static String acct(Connection c) throws SQLException { return "balance=" + one(c, "SELECT balance FROM tcc.account WHERE id = 1") + " frozen=" + one(c, "SELECT frozen FROM tcc.account WHERE id = 1"); }

    static void tcc() throws Exception {
        try (Connection c = conn()) {
            for (var e : new Object[][] { { "1_naive", NAIVE }, { "2_empty_only", EMPTY_ONLY }, { "3_guarded", GUARDED } }) {
                String name = (String) e[0]; Tcc t = (Tcc) e[1];
                reset(c);
                t.cancel(c, "g1");
                out("tcc." + name + ".empty_cancel", "Try 请求丢失、协调者超时后发来 Cancel：" + acct(c));
                reset(c);
                t.cancel(c, "g1"); t.tryReserve(c, "g1");
                out("tcc." + name + ".hanging", "Cancel 之后迟到的 Try 才到达：" + acct(c) + "（之后不会再有 Confirm 或 Cancel）");
                reset(c);
                t.tryReserve(c, "g2"); t.confirm(c, "g2"); t.confirm(c, "g2");
                out("tcc." + name + ".double_confirm", "Try 后 Confirm 被重试两次：" + acct(c));
            }
        }
    }
}
