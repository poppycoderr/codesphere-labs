import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多实例下的定时任务只跑一次：每个实例都执行、租约表选执行者、执行到一半租约过期被接管时的 fencing 与运行键、
 * cancel(true) 停不下来的任务。两个实例是同一进程里的两个对象，各自独立连接数据库，
 * 交错顺序由代码固定。
 */
public class JobOnce {

    static final String URL = "jdbc:mysql://mysql:3306/labs?useSSL=false&allowPublicKeyRetrieval=true";

    static Connection db() throws SQLException {
        return DriverManager.getConnection(URL, "root", "example_password");
    }

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static void exec(String... sqls) throws SQLException {
        try (Connection c = db(); Statement s = c.createStatement()) {
            for (String q : sqls) {
                s.execute(q);
            }
        }
    }

    static long one(String sql) throws SQLException {
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void reset() throws SQLException {
        exec("DROP TABLE IF EXISTS job_lease", "DROP TABLE IF EXISTS job_run", "DROP TABLE IF EXISTS settlement", "DROP TABLE IF EXISTS notification",
                "CREATE TABLE job_lease (job VARCHAR(30) PRIMARY KEY, owner VARCHAR(10) NULL, token BIGINT NOT NULL, expire_at DATETIME(3) NOT NULL)",
                "CREATE TABLE job_run (job VARCHAR(30), run_key VARCHAR(20), owner VARCHAR(10), token BIGINT, PRIMARY KEY (job, run_key))",
                "CREATE TABLE settlement (day VARCHAR(10) PRIMARY KEY, total INT NOT NULL, written_by VARCHAR(10) NOT NULL, token BIGINT NOT NULL)",
                "CREATE TABLE notification (id INT AUTO_INCREMENT PRIMARY KEY, day VARCHAR(10), sent_by VARCHAR(10))",
                "INSERT INTO job_lease VALUES ('daily-settlement', NULL, 0, '2000-01-01')");
    }

    /** 实例：只有名字和自己的连接。 */
    record Instance(String name) {

        /** 抢租约：条件更新，到期或自己持有才能拿到；时间用数据库的时钟，令牌每次加一。返回令牌，没抢到返回 -1。 */
        long acquire(int leaseMillis) throws SQLException {
            try (Connection c = db()) {
                PreparedStatement u = c.prepareStatement("UPDATE job_lease SET owner = ?, token = token + 1,"
                        + " expire_at = NOW(3) + INTERVAL ? MICROSECOND WHERE job = 'daily-settlement' AND (expire_at < NOW(3) OR owner = ?)");
                u.setString(1, name);
                u.setLong(2, leaseMillis * 1000L);
                u.setString(3, name);
                if (u.executeUpdate() == 0) {
                    return -1;
                }
                try (ResultSet rs = c.createStatement().executeQuery("SELECT token FROM job_lease WHERE job = 'daily-settlement'")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }

        void notifyUsers(String day) throws SQLException {
            exec("INSERT INTO notification (day, sent_by) VALUES ('" + day + "', '" + name + "')");
        }

        /** 不设防的写入：后写的覆盖先写的。 */
        void writePlain(String day, int total, long token) throws SQLException {
            exec("REPLACE INTO settlement VALUES ('" + day + "', " + total + ", '" + name + "', " + token + ")");
        }

        /** 带令牌的写入：只有令牌仍是当前租约的令牌，写入才生效；运行键在同一事务里插入，一个周期只能完成一次。 */
        String writeFenced(String day, int total, long token) throws SQLException {
            try (Connection c = db()) {
                c.setAutoCommit(false);
                PreparedStatement check = c.prepareStatement("SELECT token FROM job_lease WHERE job = 'daily-settlement' FOR UPDATE");
                try (ResultSet rs = check.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) != token) {
                        c.rollback();
                        return "拒绝（令牌 " + token + " 已过期，当前 " + rs.getLong(1) + "）";
                    }
                }
                try {
                    c.createStatement().execute("INSERT INTO job_run VALUES ('daily-settlement', '" + day + "', '" + name + "', " + token + ")");
                } catch (SQLException e) {
                    c.rollback();
                    return "拒绝（运行键 " + day + " 已存在）";
                }
                c.createStatement().execute("REPLACE INTO settlement VALUES ('" + day + "', " + total + ", '" + name + "', " + token + ")");
                c.commit();
                return "写入";
            }
        }
    }

    static String settlement(String day) throws SQLException {
        try (Connection c = db(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT total, written_by, token FROM settlement WHERE day = '" + day + "'")) {
            rs.next();
            return "total=" + rs.getInt(1) + "，由 " + rs.getString(2) + "（令牌 " + rs.getLong(3) + "）写入";
        }
    }

    public static void main(String[] args) throws Exception {
        Instance a = new Instance("A");
        Instance b = new Instance("B");

        // 一、两个实例都在 02:00 触发，都执行
        reset();
        for (Instance i : List.of(a, b)) {
            i.notifyUsers("d1");
            try {
                exec("INSERT INTO settlement VALUES ('d1', 100, '" + i.name() + "', 0)");
            } catch (SQLException e) {
                // 结算表有主键，第二次插入失败
            }
        }
        out("naive.both", "两个实例都执行：结算表 " + one("SELECT COUNT(*) FROM settlement") + " 行（主键挡住了重复），通知发了 "
                + one("SELECT COUNT(*) FROM notification") + " 次");

        // 二、租约：同时抢，只有一个拿到
        reset();
        long ta = a.acquire(1000);
        long tb = b.acquire(1000);
        out("lease.compete", "同时抢 1 秒的租约：A 得到令牌 " + ta + "，B " + (tb < 0 ? "没抢到" : "得到令牌 " + tb));

        // 三、A 执行到一半停顿 1.5 秒，租约过期，B 接管并完成；A 恢复后写入
        for (boolean fenced : new boolean[] {false, true}) {
            reset();
            long t1 = a.acquire(500);
            Thread.sleep(800);                                  // A 停顿（例如 GC），租约过期
            long t2 = b.acquire(500);
            String rb = fenced ? b.writeFenced("d1", 200, t2) : "写入";
            if (!fenced) {
                b.writePlain("d1", 200, t2);
            }
            String ra;
            if (fenced) {
                ra = a.writeFenced("d1", 150, t1);
            } else {
                a.writePlain("d1", 150, t1);
                ra = "写入";
            }
            out("takeover." + (fenced ? "fenced" : "plain"), (fenced ? "带令牌与运行键" : "不设防") + "：A 令牌 " + t1 + " 停顿后过期，B 接管得到令牌 " + t2
                    + "；B " + rb + "，A 恢复后 " + ra + "；最终 " + settlement("d1"));
        }
        // 同一个周期的第二次完成：令牌有效（A 重新抢到），但运行键已存在
        Thread.sleep(600);                                  // B 的租约过期
        long t4 = a.acquire(500);
        out("runkey", "租约过期后 A 自己重新抢到（令牌 " + t4 + "），对已完成的 d1 再写一次：" + a.writeFenced("d1", 999, t4) + "；最终 " + settlement("d1"));

        // 四、cancel(true) 停不下来的任务
        reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicLong spins = new AtomicLong();
        Future<?> cpu = pool.submit(() -> {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800);
            while (System.nanoTime() < end) {
                spins.incrementAndGet();                           // 不检查中断
            }
            exec("INSERT INTO notification (day, sent_by) VALUES ('cpu', 'task')");
            return null;
        });
        Future<?> io = pool.submit(() -> {
            try (Connection c = db(); Statement s = c.createStatement()) {
                s.execute("SELECT SLEEP(1)");                    // 阻塞在数据库调用里
                s.execute("INSERT INTO notification (day, sent_by) VALUES ('io', 'task')");
            }
            return null;
        });
        Thread.sleep(200);
        boolean c1 = cpu.cancel(true);
        boolean c2 = io.cancel(true);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        out("cancel", "cancel(true) 返回 " + c1 + "/" + c2 + "，isCancelled=" + cpu.isCancelled() + "；之后忙循环任务写入 "
                + one("SELECT COUNT(*) FROM notification WHERE day = 'cpu'") + " 行，阻塞在 SQL 里的任务写入 "
                + one("SELECT COUNT(*) FROM notification WHERE day = 'io'") + " 行");
    }
}
