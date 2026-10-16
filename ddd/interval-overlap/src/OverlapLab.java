import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 同一个成员在同一时间只能担任一个值班：按键唯一守不住，先查后插在并发下守不住，
 * 锁住成员行可以守住，PostgreSQL 的排他约束让数据库自己守住。两个事务的交错由闩锁固定。
 */
public class OverlapLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static Connection mysql() throws SQLException { return DriverManager.getConnection("jdbc:mysql://mysql:3306/labs?useSSL=false&allowPublicKeyRetrieval=true", "root", "example_password"); }
    static Connection pg() throws SQLException { return DriverManager.getConnection("jdbc:postgresql://postgres:5432/labs", "postgres", "example_password"); }
    static void exec(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }
    static long count(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }

    interface Tx { void run(Connection c) throws Exception; }

    /** 两个事务：各自先检查、在两者都检查完之后再插入；返回各自的结果 */
    static String[] race(java.util.concurrent.Callable<Connection> open, Tx check, Tx insertA, Tx insertB) throws Exception {
        CountDownLatch bothChecked = new CountDownLatch(2);
        String[] result = new String[2];
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            final int id = t;
            ts[t] = new Thread(() -> {
                try (Connection c = open.call()) {
                    c.setAutoCommit(false);
                    check.run(c);
                    bothChecked.countDown();
                    bothChecked.await(3, TimeUnit.SECONDS);
                    (id == 0 ? insertA : insertB).run(c);
                    c.commit();
                    result[id] = "提交成功";
                } catch (Exception e) { result[id] = "失败（" + e.getClass().getSimpleName() + "：" + String.valueOf(e.getMessage()).split("\n")[0].replace("ERROR: ", "") + "）"; }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        return result;
    }

    /** 两个事务谁先拿到锁不确定，所以只统计成功与失败的个数 */
    static String summary(String[] r) {
        long ok = java.util.Arrays.stream(r).filter(x -> x.equals("提交成功")).count();
        String fail = java.util.Arrays.stream(r).filter(x -> !x.equals("提交成功")).findFirst().orElse("");
        return "提交成功 " + ok + " 个" + (ok < 2 ? "，另一个" + fail : "");
    }

    public static void main(String[] args) throws Exception {
        try (Connection m = mysql(); Connection p = pg()) {
            out("env", "java.version=" + System.getProperty("java.version") + " mysql=" + m.getMetaData().getDatabaseProductVersion() + " postgres=" + p.getMetaData().getDatabaseProductVersion().split(" ")[0]);

            // 一、按键唯一
            exec(m, "DROP TABLE IF EXISTS duty"); exec(m, "DROP TABLE IF EXISTS member");
            exec(m, "CREATE TABLE member (id INT PRIMARY KEY)"); exec(m, "INSERT INTO member VALUES (1)");
            exec(m, "CREATE TABLE duty (id INT AUTO_INCREMENT PRIMARY KEY, member_id INT, starts DATETIME, ends DATETIME, UNIQUE KEY uk (member_id, starts))");
            exec(m, "INSERT INTO duty (member_id, starts, ends) VALUES (1, '2026-10-16 09:00', '2026-10-16 12:00')");
            String second;
            try { exec(m, "INSERT INTO duty (member_id, starts, ends) VALUES (1, '2026-10-16 10:00', '2026-10-16 11:00')"); second = "插入成功"; } catch (SQLException e) { second = "被拒绝"; }
            out("unique_key", "唯一键 (member_id, starts)，已有 09:00—12:00，再插入 10:00—11:00：" + second + "，现在有 " + count(m, "SELECT COUNT(*) FROM duty") + " 条重叠的值班");

            // 二、先查后插
            exec(m, "DELETE FROM duty");
            String overlap = "SELECT COUNT(*) FROM duty WHERE member_id = 1 AND starts < '%s' AND ends > '%s'";
            String[] r = race(OverlapLab::mysql,
                    c -> { if (count(c, String.format(overlap, "2026-10-16 12:00", "2026-10-16 09:00")) > 0) throw new IllegalStateException("重叠"); },
                    c -> exec(c, "INSERT INTO duty (member_id, starts, ends) VALUES (1, '2026-10-16 09:00', '2026-10-16 12:00')"),
                    c -> exec(c, "INSERT INTO duty (member_id, starts, ends) VALUES (1, '2026-10-16 10:00', '2026-10-16 13:00')"));
            out("check_then_insert", "两个事务各自先查「有没有重叠」再插入（REPEATABLE READ）：" + summary(r) + "；表里重叠的值班 " + count(m, "SELECT COUNT(*) FROM duty"));

            // 三、锁住成员行
            exec(m, "DELETE FROM duty");
            r = race(OverlapLab::mysql,
                    c -> { count(c, "SELECT COUNT(*) FROM member WHERE id = 1 FOR UPDATE"); if (count(c, String.format(overlap, "2026-10-16 12:00", "2026-10-16 09:00")) > 0) throw new IllegalStateException("重叠"); },
                    c -> exec(c, "INSERT INTO duty (member_id, starts, ends) VALUES (1, '2026-10-16 09:00', '2026-10-16 12:00')"),
                    c -> exec(c, "INSERT INTO duty (member_id, starts, ends) VALUES (1, '2026-10-16 10:00', '2026-10-16 13:00')"));
            out("lock_member", "检查前先 SELECT … FOR UPDATE 锁住成员行（后到的事务在这里等待先到的提交）：" + summary(r) + "；表里的值班 " + count(m, "SELECT COUNT(*) FROM duty"));

            // 四、PostgreSQL 排他约束
            exec(p, "CREATE EXTENSION IF NOT EXISTS btree_gist");
            exec(p, "DROP TABLE IF EXISTS duty");
            exec(p, "CREATE TABLE duty (id SERIAL PRIMARY KEY, member_id INT, period TSRANGE, EXCLUDE USING gist (member_id WITH =, period WITH &&))");
            r = race(OverlapLab::pg, c -> { },
                    c -> exec(c, "INSERT INTO duty (member_id, period) VALUES (1, '[2026-10-16 09:00, 2026-10-16 12:00)')"),
                    c -> exec(c, "INSERT INTO duty (member_id, period) VALUES (1, '[2026-10-16 10:00, 2026-10-16 13:00)')"));
            out("pg_exclude", "PostgreSQL 排他约束 EXCLUDE (member_id WITH =, period WITH &&)，两个事务并发插入重叠区间：" + summary(r) + "；表里的值班 " + count(p, "SELECT COUNT(*) FROM duty"));
            exec(p, "INSERT INTO duty (member_id, period) VALUES (1, '[2026-10-17 09:00, 2026-10-17 12:00)')");
            String adj;
            try { exec(p, "INSERT INTO duty (member_id, period) VALUES (1, '[2026-10-17 12:00, 2026-10-17 15:00)')"); adj = "插入成功"; } catch (SQLException e) { adj = "被拒绝"; }
            String closed;
            try { exec(p, "INSERT INTO duty (member_id, period) VALUES (1, '[2026-10-17 15:00, 2026-10-17 18:00]')"); exec(p, "INSERT INTO duty (member_id, period) VALUES (1, '[2026-10-17 18:00, 2026-10-17 20:00)')"); closed = "插入成功"; } catch (SQLException e) { closed = "被拒绝（18:00 同时属于两段）"; }
            out("pg_boundaries", "另一天：半开区间 [09:00, 12:00) 之后接 [12:00, 15:00)：" + adj + "；闭区间 [15:00, 18:00] 之后接 [18:00, 20:00)：" + closed);
            String other;
            try { exec(p, "INSERT INTO duty (member_id, period) VALUES (2, '[2026-10-16 09:00, 2026-10-16 12:00)')"); other = "插入成功"; } catch (SQLException e) { other = "被拒绝"; }
            out("pg_other_member", "另一个成员同一时段：" + other);
        }
    }
}
