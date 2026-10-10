import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 两个测试共用一个数据库时互相影响的几种方式，以及各种隔离办法管到哪里。
 * 「测试 A」「测试 B」各用一条连接，按固定顺序交替执行它们的准备、断言、清理步骤，结果是确定的。
 */
public class TestDataLab {
    static final String HOST = "jdbc:mysql://127.0.0.1:3306/", OPTS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static Connection open(String db) throws SQLException { return DriverManager.getConnection(HOST + db + OPTS, "root", "example_password"); }

    static long one(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }
    static int exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }
    static String tryExec(Connection c, String sql, Object... args) {
        try { exec(c, sql, args); return "成功"; } catch (SQLException e) { return "失败（" + e.getClass().getSimpleName() + "）"; }
    }
    static String verdict(boolean pass) { return pass ? "通过" : "失败"; }
    static void schema(Connection c) throws SQLException {
        exec(c, "DROP TABLE IF EXISTS orders, users");
        exec(c, "CREATE TABLE users (id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(64) NOT NULL UNIQUE, run_id VARCHAR(16))");
        exec(c, "CREATE TABLE orders (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_email VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, run_id VARCHAR(16))");
    }

    public static void main(String[] args) throws Exception {
        try (Connection a = open("labs"); Connection b = open("labs")) {
            out("env", "java.version=" + System.getProperty("java.version") + " mysql=" + a.getMetaData().getDatabaseProductVersion());

            // 一、两个测试写了同一条固定数据
            schema(a);
            String insertA = tryExec(a, "INSERT INTO users (email) VALUES ('test@example.com')");
            String insertB = tryExec(b, "INSERT INTO users (email) VALUES ('test@example.com')");
            out("fixed.same_row", "两个测试的准备步骤都插入 test@example.com：A " + insertA + "，B " + insertB);

            // 二、断言数的是整张表
            schema(a);
            exec(a, "INSERT INTO orders (user_email, status) VALUES ('a@example.com', 'NEW')");
            exec(b, "INSERT INTO orders (user_email, status) VALUES ('b@example.com', 'NEW')");
            long all = one(a, "SELECT COUNT(*) FROM orders WHERE status = 'NEW'");
            out("count.whole_table", "A 下了一单后断言「NEW 状态的订单有 1 条」，此时 B 也下了一单：实际 " + all + " 条，A " + verdict(all == 1));
            long own = one(a, "SELECT COUNT(*) FROM orders WHERE status = 'NEW' AND user_email = 'a@example.com'");
            out("count.scoped", "断言改成只数自己那个用户的订单：实际 " + own + " 条，A " + verdict(own == 1));

            // 三、清理步骤删掉了别人的数据
            schema(a);
            exec(a, "INSERT INTO users (email) VALUES ('test-a@example.com')");
            exec(b, "INSERT INTO users (email) VALUES ('test-b@example.com')");
            int deleted = exec(b, "DELETE FROM users WHERE email LIKE 'test%'");                    // B 先跑完，执行清理
            long stillThere = one(a, "SELECT COUNT(*) FROM users WHERE email = 'test-a@example.com'");
            out("cleanup.pattern", "B 结束时清理 DELETE … WHERE email LIKE 'test%'：删了 " + deleted + " 行；A 随后查自己刚插入的用户：" + stillThere + " 行，A " + verdict(stillThere == 1));
            exec(a, "INSERT INTO users (email, run_id) VALUES ('test-a@example.com', 'run-a')");
            exec(b, "INSERT INTO users (email, run_id) VALUES ('test-b@example.com', 'run-b')");
            deleted = exec(b, "DELETE FROM users WHERE run_id = 'run-b'");
            stillThere = one(a, "SELECT COUNT(*) FROM users WHERE email = 'test-a@example.com'");
            out("cleanup.owned", "每条数据带上创建它的那次运行的标识，清理只删自己的：删了 " + deleted + " 行；A 查自己的用户：" + stillThere + " 行，A " + verdict(stillThere == 1));

            // 四、上一次运行中途退出，留下了数据
            schema(a);
            exec(a, "INSERT INTO users (email) VALUES ('test@example.com')");                         // 上一次运行插入后崩溃，没有走到清理
            out("leftover.fixed", "上一次运行没走到清理就退出了；这一次的准备步骤再插入 test@example.com：" + tryExec(b, "INSERT INTO users (email) VALUES ('test@example.com')"));
            out("leftover.unique", "邮箱里带上本次运行的标识（u-7f3a@example.com）：" + tryExec(b, "INSERT INTO users (email, run_id) VALUES ('u-7f3a@example.com', '7f3a')")
                    + "；上次留下的那行仍在表里 = " + (one(b, "SELECT COUNT(*) FROM users WHERE email = 'test@example.com'") == 1));

            // 五、用事务回滚来隔离：别的连接看不到测试准备的数据
            schema(a);
            a.setAutoCommit(false);
            exec(a, "INSERT INTO users (email) VALUES ('tx@example.com')");
            long seenBySelf = one(a, "SELECT COUNT(*) FROM users WHERE email = 'tx@example.com'");
            long seenByOther = one(b, "SELECT COUNT(*) FROM users WHERE email = 'tx@example.com'");
            out("rollback.visibility", "测试在一个不提交的事务里准备数据：同一条连接看到 " + seenBySelf + " 行；被测代码如果用另一条连接（异步线程、新开的事务、另一个进程）看到 " + seenByOther + " 行");
            exec(b, "SET SESSION innodb_lock_wait_timeout = 1");
            out("rollback.blocking", "这个事务还没结束时，另一个测试插入同一个邮箱：" + tryExec(b, "INSERT INTO users (email) VALUES ('tx@example.com')") + "（等了 1 秒的锁）");
            a.rollback(); a.setAutoCommit(true);
            exec(a, "INSERT INTO users (email) VALUES ('after@example.com')");
            out("rollback.auto_increment", "回滚之后再插入一行，它的自增 ID = " + one(a, "SELECT id FROM users WHERE email = 'after@example.com'") + "（表里只有这 1 行）");

            // 六、每个测试进程一个库
            exec(a, "DROP DATABASE IF EXISTS labs_w1"); exec(a, "DROP DATABASE IF EXISTS labs_w2");
            exec(a, "CREATE DATABASE labs_w1"); exec(a, "CREATE DATABASE labs_w2");
            try (Connection w1 = open("labs_w1"); Connection w2 = open("labs_w2")) {
                schema(w1); schema(w2);
                String r1 = tryExec(w1, "INSERT INTO users (email) VALUES ('test@example.com')"), r2 = tryExec(w2, "INSERT INTO users (email) VALUES ('test@example.com')");
                exec(w1, "INSERT INTO orders (user_email, status) VALUES ('test@example.com', 'NEW')"); exec(w2, "INSERT INTO orders (user_email, status) VALUES ('test@example.com', 'NEW')");
                long c1 = one(w1, "SELECT COUNT(*) FROM orders WHERE status = 'NEW'");
                int wiped = exec(w2, "DELETE FROM users WHERE email LIKE 'test%'");
                out("per_worker_db", "两个并行的测试进程各用一个库，都用固定的 test@example.com：插入 " + r1 + " / " + r2 + "；进程 1 数整张表的断言得到 " + c1 + "；进程 2 按模式清理删了 " + wiped
                        + " 行，进程 1 的用户还在 = " + (one(w1, "SELECT COUNT(*) FROM users") == 1));
            }
            exec(a, "DROP DATABASE labs_w1"); exec(a, "DROP DATABASE labs_w2");
        }
    }
}
