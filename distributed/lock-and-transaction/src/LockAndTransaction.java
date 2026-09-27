import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 锁与事务的边界、先查后改与条件更新、三种主键的插入代价，输出为「键<TAB>事实」。
 * 锁用进程内的 ReentrantLock 模拟一把工作正常的分布式锁，排除锁实现本身的问题，只看它和事务提交的先后。
 * 运行：java -cp <mysql-connector> src/LockAndTransaction.java，连接 127.0.0.1:3306/labs。
 */
public class LockAndTransaction {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true&rewriteBatchedStatements=true";
    static final int USERS = 200;
    static final int THREADS_PER_USER = 20;
    static final int ORDERS = 1000;
    static final int PK_ROWS = 200_000;

    enum Placement { LOCK_INSIDE_TX, LOCK_AROUND_TX }

    public static void main(String[] args) throws Exception {
        lockVsTransaction(Placement.LOCK_INSIDE_TX, "user_order");
        lockVsTransaction(Placement.LOCK_AROUND_TX, "user_order");
        lockVsTransaction(Placement.LOCK_INSIDE_TX, "user_order_unique");
        transition(false);
        transition(true);
        primaryKeys();
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, "root", "example_password");
    }

    // ---------- 1. 锁在事务里面还是外面 ----------

    /** 每个用户 20 个线程同时下单，业务是「没有订单就插入一条」；统计有重复订单的用户数。 */
    static void lockVsTransaction(Placement placement, String table) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE " + table);
        }
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS_PER_USER);
        for (long user = 1; user <= USERS; user++) {
            ReentrantLock lock = new ReentrantLock();                // 一个用户一把锁，锁 key 是 userId
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < THREADS_PER_USER; t++) {
                long userId = user;
                fs.add(pool.submit(() -> {
                    start.await();
                    try (Connection c = connect()) {
                        placeOrder(c, lock, placement, table, userId);
                    } catch (SQLIntegrityConstraintViolationException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : fs) {
                f.get();
            }
        }
        pool.shutdown();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*), COUNT(DISTINCT user_id), "
                     + "(SELECT COUNT(*) FROM (SELECT user_id FROM " + table + " GROUP BY user_id HAVING COUNT(*) > 1) d) FROM " + table)) {
            rs.next();
            String key = "lock." + placement + (table.endsWith("unique") ? ".unique" : "");
            out(key, "%d 个用户各 %d 个线程并发下单：订单 %d 条，有订单的用户 %d 个，出现重复订单的用户 %d 个，唯一键拒绝 %d 次".formatted(
                    USERS, THREADS_PER_USER, rs.getInt(1), rs.getInt(2), rs.getInt(3), rejected.get()));
        }
    }

    static void placeOrder(Connection c, ReentrantLock lock, Placement placement, String table, long userId) throws SQLException {
        if (placement == Placement.LOCK_INSIDE_TX) {
            c.setAutoCommit(false);
            lock.lock();
            try {
                checkAndInsert(c, table, userId);
            } finally {
                lock.unlock();                                   // 提交前释放：下一个线程拿到锁时，这条插入还没提交
            }
            c.commit();
        } else {
            lock.lock();
            try {
                c.setAutoCommit(false);
                checkAndInsert(c, table, userId);
                c.commit();
            } finally {
                lock.unlock();
            }
        }
    }

    static void checkAndInsert(Connection c, String table, long userId) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?")) {
            q.setLong(1, userId);
            try (ResultSet rs = q.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
        }
        try (PreparedStatement i = c.prepareStatement("INSERT INTO " + table + " (user_id) VALUES (?)")) {
            i.setLong(1, userId);
            i.executeUpdate();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        }
    }

    // ---------- 2. 先查后改 vs 条件更新 ----------

    /**
     * 1000 笔待支付订单，「支付回调」与「超时关闭」两个线程逐笔处理，每一笔都在同一时刻开始（CyclicBarrier），
     * 模拟两条路径恰好撞上同一笔订单。
     */
    static void transition(boolean conditional) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE pay_order");
            StringBuilder sql = new StringBuilder("INSERT INTO pay_order (id, status) VALUES ");
            for (int i = 1; i <= ORDERS; i++) {
                sql.append(i == 1 ? "" : ",").append("(").append(i).append(",'CREATED')");
            }
            s.execute(sql.toString());
        }
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger paid = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> a = pool.submit(() -> path(conditional, barrier, "PAID", "paid_at", paid));
        Future<?> b = pool.submit(() -> path(conditional, barrier, "CLOSED", "closed_at", closed));
        a.get();
        b.get();
        pool.shutdown();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT SUM(paid_at IS NOT NULL AND closed_at IS NOT NULL), SUM(status = 'PAID'), SUM(status = 'CLOSED') FROM pay_order")) {
            rs.next();
            out(conditional ? "transition.conditional" : "transition.check_then_act",
                    "%d 笔订单：支付路径成功 %d 次，关闭路径成功 %d 次，同时有支付时间和关闭时间的订单 %d 笔，最终状态 PAID %d / CLOSED %d".formatted(
                            ORDERS, paid.get(), closed.get(), rs.getInt(1), rs.getInt(2), rs.getInt(3)));
        }
    }

    static Void path(boolean conditional, CyclicBarrier barrier, String target, String column, AtomicInteger success) throws Exception {
        try (Connection c = connect();
             PreparedStatement read = c.prepareStatement("SELECT status FROM pay_order WHERE id = ?");
             PreparedStatement blind = c.prepareStatement("UPDATE pay_order SET status = ?, " + column + " = NOW(6) WHERE id = ?");
             PreparedStatement guarded = c.prepareStatement("UPDATE pay_order SET status = ?, " + column + " = NOW(6) WHERE id = ? AND status = 'CREATED'")) {
            for (int id = 1; id <= ORDERS; id++) {
                barrier.await();
                if (conditional) {
                    guarded.setString(1, target);
                    guarded.setLong(2, id);
                    if (guarded.executeUpdate() == 1) {
                        success.incrementAndGet();
                    }
                } else {
                    read.setLong(1, id);
                    String status;
                    try (ResultSet rs = read.executeQuery()) {
                        rs.next();
                        status = rs.getString(1);
                    }
                    if ("CREATED".equals(status)) {
                        blind.setString(1, target);
                        blind.setLong(2, id);
                        blind.executeUpdate();
                        success.incrementAndGet();
                    }
                }
            }
        }
        return null;
    }

    // ---------- 3. 主键类型 ----------

    static final String[][] PK = {
            {"BIGINT 自增", "id BIGINT AUTO_INCREMENT PRIMARY KEY", "INSERT INTO pk_test (order_no, user_id, amount) VALUES (?, ?, ?)"},
            {"BINARY(16) 有序 UUID", "id BINARY(16) PRIMARY KEY", "INSERT INTO pk_test (id, order_no, user_id, amount) VALUES (UUID_TO_BIN(UUID(), 1), ?, ?, ?)"},
            {"CHAR(36) 随机 UUID", "id CHAR(36) PRIMARY KEY", "INSERT INTO pk_test (id, order_no, user_id, amount) VALUES (?, ?, ?, ?)"},
    };

    /** 20 万行、每批 1,000 行，每种主键建表插入 3 次取中位数；空间取最后一次 ANALYZE 之后的统计。 */
    static void primaryKeys() throws Exception {
        for (String[] pk : PK) {
            long[] ms = new long[3];
            long data = 0;
            long index = 0;
            for (int run = 0; run < 3; run++) {
                try (Connection c = connect(); Statement s = c.createStatement()) {
                    s.execute("DROP TABLE IF EXISTS pk_test");
                    s.execute("CREATE TABLE pk_test (" + pk[1] + ", order_no VARCHAR(32) NOT NULL, user_id BIGINT NOT NULL, amount INT NOT NULL, "
                            + "UNIQUE KEY uk_order_no (order_no), KEY idx_user (user_id))");
                    c.setAutoCommit(false);
                    long begin = System.nanoTime();
                    try (PreparedStatement p = c.prepareStatement(pk[2])) {
                        for (int i = 0; i < PK_ROWS; i++) {
                            int k = 1;
                            if (pk[0].startsWith("CHAR")) {
                                p.setString(k++, UUID.randomUUID().toString());
                            }
                            p.setString(k++, "ORD%012d".formatted(i));
                            p.setLong(k++, i % 5000);
                            p.setInt(k, i % 997);
                            p.addBatch();
                            if (i % 1000 == 999) {
                                p.executeBatch();
                                c.commit();
                            }
                        }
                    }
                    ms[run] = (System.nanoTime() - begin) / 1_000_000;
                    c.setAutoCommit(true);
                    s.execute("ANALYZE TABLE pk_test");
                    try (ResultSet rs = s.executeQuery("SELECT data_length, index_length FROM information_schema.tables WHERE table_schema = 'labs' AND table_name = 'pk_test'")) {
                        rs.next();
                        data = rs.getLong(1);
                        index = rs.getLong(2);
                    }
                }
            }
            long[] sorted = ms.clone();
            Arrays.sort(sorted);
            out("pk." + pk[0].split(" ")[0], "%s：插入 %,d 行 %d ms（3 次 %s 的中位数），数据 %.1f MB，二级索引 %.1f MB，合计 %.1f MB".formatted(
                    pk[0], PK_ROWS, sorted[1], Arrays.toString(ms), data / 1048576.0, index / 1048576.0, (data + index) / 1048576.0));
        }
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
