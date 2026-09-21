import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// 活动名额 100，200 个并发报名请求；对比「查询后插入」与「经聚合根版本号更新」
public class Oversell {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/ddd?useSSL=false&allowPublicKeyRetrieval=true";
    static final int CAP = 100, REQ = 200;

    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true", "root", "example_password");
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS ddd"); s.execute("CREATE DATABASE ddd"); s.execute("USE ddd");
            s.execute("CREATE TABLE event_session (id BIGINT PRIMARY KEY, capacity INT NOT NULL, booked INT NOT NULL, version INT NOT NULL)");
            s.execute("CREATE TABLE registration (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id BIGINT NOT NULL, attendee VARCHAR(32) NOT NULL, UNIQUE KEY uk (session_id, attendee))");
            s.execute("INSERT INTO event_session VALUES (1, " + CAP + ", 0, 0), (2, " + CAP + ", 0, 0), (3, " + CAP + ", 0, 0), (4, " + CAP + ", 0, 0)");
        }
        for (int round = 1; round <= 3; round++) {
            reset();
            AtomicInteger okA = new AtomicInteger();
            run(i -> { if (checkThenInsert(1, "a" + i)) okA.incrementAndGet(); });
            AtomicInteger okB = new AtomicInteger(), conflicts = new AtomicInteger(), full = new AtomicInteger(), gaveUp = new AtomicInteger();
            long t0 = System.nanoTime();
            run(i -> { switch (viaAggregate(2, "b" + i, conflicts)) { case "OK" -> okB.incrementAndGet(); case "FULL" -> full.incrementAndGet(); default -> gaveUp.incrementAndGet(); } });
            long tB = (System.nanoTime() - t0) / 1_000_000;
            AtomicInteger okC = new AtomicInteger(), fullC = new AtomicInteger();
            t0 = System.nanoTime();
            run(i -> { if (lockRoot(3, "c" + i)) okC.incrementAndGet(); else fullC.incrementAndGet(); });
            long tC = (System.nanoTime() - t0) / 1_000_000;
            AtomicInteger okD = new AtomicInteger(), fullD = new AtomicInteger();
            t0 = System.nanoTime();
            run(i -> { if (conditional(4, "d" + i)) okD.incrementAndGet(); else fullD.incrementAndGet(); });
            long tD = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("第 %d 轮%n  A 查询后插入：成功 %d，实际行数 %d%n  B 版本号：成功 %d，满员 %d，放弃 %d，冲突 %d 次，行数 %d，%dms%n  C FOR UPDATE：成功 %d，满员 %d，行数 %d，%dms%n  D 条件更新：成功 %d，满员 %d，行数 %d，%dms%n",
                round, okA.get(), count(1), okB.get(), full.get(), gaveUp.get(), conflicts.get(), count(2), tB, okC.get(), fullC.get(), count(3), tC, okD.get(), fullD.get(), count(4), tD);
        }
    }

    interface Task { void run(int i) throws Exception; }
    static void run(Task t) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQ);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < REQ; i++) { int n = i; fs.add(pool.submit(() -> { go.await(); t.run(n); return null; })); }
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
    }

    // 规则写在服务里：先数一数，再插入
    static boolean checkThenInsert(long sid, String who) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            int cap, used;
            try (PreparedStatement p = c.prepareStatement("SELECT capacity FROM event_session WHERE id=?")) { p.setLong(1, sid); ResultSet r = p.executeQuery(); r.next(); cap = r.getInt(1); }
            try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM registration WHERE session_id=?")) { p.setLong(1, sid); ResultSet r = p.executeQuery(); r.next(); used = r.getInt(1); }
            if (used >= cap) { c.rollback(); return false; }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO registration(session_id, attendee) VALUES (?,?)")) { p.setLong(1, sid); p.setString(2, who); p.executeUpdate(); }
            c.commit(); return true;
        }
    }

    // 场次是聚合根：名额规则由它守住，修改必须经过它的版本号
    static String viaAggregate(long sid, String who, AtomicInteger conflicts) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            for (int attempt = 0; attempt < 50; attempt++) {
                int cap, booked, ver;
                try (PreparedStatement p = c.prepareStatement("SELECT capacity, booked, version FROM event_session WHERE id=?")) {
                    p.setLong(1, sid); ResultSet r = p.executeQuery(); r.next(); cap = r.getInt(1); booked = r.getInt(2); ver = r.getInt(3);
                }
                if (booked >= cap) { c.rollback(); return "FULL"; }
                int n;
                try (PreparedStatement p = c.prepareStatement("UPDATE event_session SET booked=booked+1, version=version+1 WHERE id=? AND version=?")) {
                    p.setLong(1, sid); p.setInt(2, ver); n = p.executeUpdate();
                }
                if (n == 0) { c.rollback(); conflicts.incrementAndGet(); continue; }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO registration(session_id, attendee) VALUES (?,?)")) { p.setLong(1, sid); p.setString(2, who); p.executeUpdate(); }
                c.commit(); return "OK";
            }
            return "GIVE_UP";
        }
    }

    // 悲观锁：先锁住聚合根，再检查规则
    static boolean lockRoot(long sid, String who) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            int cap, booked;
            try (PreparedStatement p = c.prepareStatement("SELECT capacity, booked FROM event_session WHERE id=? FOR UPDATE")) {
                p.setLong(1, sid); ResultSet r = p.executeQuery(); r.next(); cap = r.getInt(1); booked = r.getInt(2);
            }
            if (booked >= cap) { c.rollback(); return false; }
            try (PreparedStatement p = c.prepareStatement("UPDATE event_session SET booked=booked+1 WHERE id=?")) { p.setLong(1, sid); p.executeUpdate(); }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO registration(session_id, attendee) VALUES (?,?)")) { p.setLong(1, sid); p.setString(2, who); p.executeUpdate(); }
            c.commit(); return true;
        }
    }

    // 条件更新：把不变量写进一条原子语句
    static boolean conditional(long sid, String who) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            int n;
            try (PreparedStatement p = c.prepareStatement("UPDATE event_session SET booked=booked+1 WHERE id=? AND booked < capacity")) { p.setLong(1, sid); n = p.executeUpdate(); }
            if (n == 0) { c.rollback(); return false; }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO registration(session_id, attendee) VALUES (?,?)")) { p.setLong(1, sid); p.setString(2, who); p.executeUpdate(); }
            c.commit(); return true;
        }
    }

    static Connection conn() throws SQLException { return DriverManager.getConnection(URL, "root", "example_password"); }
    static void reset() throws SQLException { try (Connection c = conn(); Statement s = c.createStatement()) { s.execute("DELETE FROM registration"); s.execute("UPDATE event_session SET booked=0, version=0"); } }
    static int count(long sid) throws SQLException { try (Connection c = conn(); Statement s = c.createStatement()) { ResultSet r = s.executeQuery("SELECT COUNT(*) FROM registration WHERE session_id=" + sid); r.next(); return r.getInt(1); } }
}
