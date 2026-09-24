import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 聚合边界的三组对照，输出为「键<TAB>事实」：
 * 1. 候补递补放在场次聚合内（同一事务）还是拆成独立的候补聚合（事件驱动递补）：新报名能否插队；
 * 2. 以活动为根的大聚合与以场次为根的小聚合：同样 200 个请求分散在 8 个场次上的锁等待与耗时；
 * 3. 领域方法里直接发布事件与提交后发布：事务回滚时事件是否已经流出。
 */
public class Aggregates {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true";

    public static void main(String[] args) throws Exception {
        for (int round = 1; round <= 3; round++) {
            promotion("combined", round);
            promotion("split", round);
        }
        for (int round = 1; round <= 3; round++) {
            contention("event-root", round);
            contention("session-root", round);
        }
        eventsOnRollback();
        rootOnlyModification();
    }

    // ---------- 1. 候补递补：同一聚合 vs 拆成两个聚合 ----------

    static final int CAP = 20, WAITING = 20, PAIRS = 10;
    static final AtomicInteger DEADLOCKS = new AtomicInteger();

    interface Tx { void run() throws SQLException; }

    /** 死锁（1213）时整笔事务重试，并计数。 */
    static void retry(Tx tx) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try { tx.run(); return; } catch (SQLException e) {
                if (e.getErrorCode() != 1213 || attempt == 10) throw e;
                DEADLOCKS.incrementAndGet();
            }
        }
    }

    static void promotion(String design, int round) throws Exception {
        reset();
        exec("INSERT INTO event VALUES (1, 'DDD 工作坊')");
        exec("INSERT INTO session (id, event_id, capacity) VALUES (1, 1, " + CAP + ")");
        exec("INSERT INTO waitlist VALUES (1)");
        for (int i = 0; i < CAP; i++) combinedRegister(1, "early" + i);
        for (int i = 0; i < WAITING; i++) combinedRegister(1, "wait" + i);

        DEADLOCKS.set(0);
        BlockingQueue<Long> seatReleased = new LinkedBlockingQueue<>();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger promoted = new AtomicInteger(), promotionMissed = new AtomicInteger();
        Thread promoter = new Thread(() -> {
            try {
                while (!stop.get() || !seatReleased.isEmpty()) {
                    Long sid = seatReleased.poll(10, TimeUnit.MILLISECONDS);
                    if (sid == null) continue;
                    Thread.sleep(5); // 事件从提交到被处理的投递延迟
                    boolean[] ok = new boolean[1];
                    retry(() -> ok[0] = splitPromote(sid));
                    if (ok[0]) promoted.incrementAndGet(); else promotionMissed.incrementAndGet();
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
        promoter.setDaemon(true);
        if (design.equals("split")) promoter.start();

        ExecutorService pool = Executors.newFixedThreadPool(PAIRS * 2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < PAIRS; i++) {
            int n = i;
            fs.add(pool.submit(() -> { go.await(); if (design.equals("combined")) retry(() -> combinedCancel(1, "early" + n)); else { retry(() -> splitCancel(1, "early" + n)); seatReleased.add(1L); } return null; }));
            fs.add(pool.submit(() -> { go.await(); Thread.sleep(2); if (design.equals("combined")) retry(() -> combinedRegister(1, "late" + n)); else retry(() -> splitRegister(1, "late" + n)); return null; }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        stop.set(true);
        if (design.equals("split")) promoter.join();

        int lateConfirmed = count("SELECT COUNT(*) FROM registration WHERE attendee LIKE 'late%' AND status='CONFIRMED'");
        int waitConfirmed = count("SELECT COUNT(*) FROM registration WHERE attendee LIKE 'wait%' AND status='CONFIRMED'");
        int confirmed = count("SELECT COUNT(*) FROM registration WHERE status='CONFIRMED'");
        String extra = (design.equals("split") ? "，递补成功 " + promoted.get() + " 次、递补时已无空位 " + promotionMissed.get() + " 次" : "")
                + "，死锁重试 " + DEADLOCKS.get() + " 次";
        System.out.printf("promotion.%s.%d\t10 人取消、10 位新报名并发：已确认 %d/%d，候补者递补 %d，新报名直接确认 %d%s%n",
                design, round, confirmed, CAP, waitConfirmed, lateConfirmed, extra);
    }

    /** 场次聚合包含候补队列：报名时先看候补是否为空，取消时在同一事务里递补。 */
    static void combinedRegister(long sid, String who) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            int[] s = lockSession(c, sid);
            boolean seatFree = s[1] < s[0] && s[2] == 0;
            insert(c, sid, who, seatFree ? "CONFIRMED" : "WAITLISTED");
            update(c, "UPDATE session SET " + (seatFree ? "confirmed_count" : "waitlisted_count") + " = " + (seatFree ? "confirmed_count" : "waitlisted_count") + " + 1 WHERE id=?", sid);
            c.commit();
        }
    }

    static void combinedCancel(long sid, String who) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            lockSession(c, sid);
            update(c, "UPDATE registration SET status='CANCELLED' WHERE session_id=? AND attendee='" + who + "' AND status='CONFIRMED'", sid);
            String next = firstWaiting(c, sid);
            if (next == null) {
                update(c, "UPDATE session SET confirmed_count = confirmed_count - 1 WHERE id=?", sid);
            } else {
                update(c, "UPDATE registration SET status='CONFIRMED' WHERE session_id=? AND attendee='" + next + "'", sid);
                update(c, "UPDATE session SET waitlisted_count = waitlisted_count - 1 WHERE id=?", sid);
            }
            c.commit();
        }
    }

    /** 拆分写法：场次聚合只管名额，候补队列是另一个聚合，由「名额已释放」事件驱动递补。 */
    static void splitRegister(long sid, String who) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            int[] s = lockSession(c, sid);
            if (s[1] < s[0]) {
                insert(c, sid, who, "CONFIRMED");
                update(c, "UPDATE session SET confirmed_count = confirmed_count + 1 WHERE id=?", sid);
                c.commit();
                return;
            }
            c.commit();
        }
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            lockWaitlist(c, sid);
            insert(c, sid, who, "WAITLISTED");
            c.commit();
        }
    }

    static void splitCancel(long sid, String who) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            lockSession(c, sid);
            update(c, "UPDATE registration SET status='CANCELLED' WHERE session_id=? AND attendee='" + who + "' AND status='CONFIRMED'", sid);
            update(c, "UPDATE session SET confirmed_count = confirmed_count - 1 WHERE id=?", sid);
            c.commit();
        }
    }

    static boolean splitPromote(long sid) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            lockWaitlist(c, sid);
            int[] s = lockSession(c, sid);
            String next = firstWaiting(c, sid);
            if (next == null) { c.commit(); return false; }
            if (s[1] >= s[0]) { c.commit(); return false; }
            update(c, "UPDATE registration SET status='CONFIRMED' WHERE session_id=? AND attendee='" + next + "'", sid);
            update(c, "UPDATE session SET confirmed_count = confirmed_count + 1 WHERE id=?", sid);
            c.commit();
            return true;
        }
    }

    // ---------- 2. 大聚合与小聚合 ----------

    static final int SESSIONS = 8, REQUESTS = 200;

    static void contention(String design, int round) throws Exception {
        reset();
        exec("INSERT INTO event VALUES (1, 'DDD 工作坊')");
        for (int s = 1; s <= SESSIONS; s++) exec("INSERT INTO session (id, event_id, capacity) VALUES (" + s + ", 1, 1000)");
        long waitsBefore = status("Innodb_row_lock_waits");
        long timeBefore = status("Innodb_row_lock_time");
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            int n = i;
            fs.add(pool.submit(() -> {
                go.await();
                long sid = 1 + n % SESSIONS;
                try (Connection c = conn()) {
                    c.setAutoCommit(false);
                    if (design.equals("event-root")) {
                        try (PreparedStatement p = c.prepareStatement("SELECT id FROM event WHERE id=1 FOR UPDATE")) { p.executeQuery().next(); }
                    }
                    int[] s = lockSession(c, sid);
                    Thread.sleep(2); // 加载聚合、执行规则的耗时，期间持有锁
                    if (s[1] < s[0]) {
                        insert(c, sid, "u" + n, "CONFIRMED");
                        update(c, "UPDATE session SET confirmed_count = confirmed_count + 1 WHERE id=?", sid);
                    }
                    c.commit();
                }
                return null;
            }));
        }
        long t0 = System.nanoTime();
        go.countDown();
        for (Future<?> f : fs) f.get();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        long waits = status("Innodb_row_lock_waits") - waitsBefore;
        long lockMs = status("Innodb_row_lock_time") - timeBefore;
        int rows = count("SELECT COUNT(*) FROM registration WHERE status='CONFIRMED'");
        System.out.printf("contention.%s.%d\t%d 个请求分散在 %d 个场次：确认 %d，行锁等待 %d 次，累计锁等待 %dms，总耗时 %dms%n",
                design, round, REQUESTS, SESSIONS, rows, waits, lockMs, ms);
    }

    // ---------- 3. 事件在回滚时是否流出 ----------

    record SeatTaken(long sessionId, String attendee) {}

    /** 只加载计数、不加载全部报名的场次聚合；重复报名由唯一索引兜底。 */
    static final class SessionAggregate {
        final long id; final int capacity; int confirmed;
        final List<SeatTaken> pending = new ArrayList<>();
        final List<SeatTaken> leakedByDirectPublish;
        SessionAggregate(long id, int capacity, int confirmed, List<SeatTaken> direct) { this.id = id; this.capacity = capacity; this.confirmed = confirmed; this.leakedByDirectPublish = direct; }
        void register(String who) {
            if (confirmed >= capacity) throw new IllegalStateException("满员");
            confirmed++;
            SeatTaken e = new SeatTaken(id, who);
            if (leakedByDirectPublish != null) leakedByDirectPublish.add(e); else pending.add(e);
        }
        List<SeatTaken> drain() { List<SeatTaken> out = List.copyOf(pending); pending.clear(); return out; }
    }

    static void eventsOnRollback() throws Exception {
        reset();
        exec("INSERT INTO event VALUES (1, 'DDD 工作坊')");
        exec("INSERT INTO session (id, event_id, capacity, confirmed_count) VALUES (1, 1, 10, 1)");
        exec("INSERT INTO registration (session_id, attendee, status) VALUES (1, 'alice', 'CONFIRMED')");
        for (String mode : List.of("direct", "after-commit")) {
            List<SeatTaken> published = new ArrayList<>();
            String error = "";
            try (Connection c = conn()) {
                c.setAutoCommit(false);
                int[] s = lockSession(c, 1);
                SessionAggregate agg = new SessionAggregate(1, s[0], s[1], mode.equals("direct") ? published : null);
                agg.register("alice"); // 聚合只知道计数，不知道 alice 已经报过名
                try {
                    insert(c, 1, "alice", "CONFIRMED");
                    update(c, "UPDATE session SET confirmed_count = confirmed_count + 1 WHERE id=?", 1);
                    c.commit();
                    published.addAll(agg.drain());
                } catch (SQLException e) {
                    c.rollback();
                    error = e.getMessage().replaceAll(" for key .*", "");
                }
            }
            System.out.printf("rollback.%s\t保存失败（%s）并回滚：确认数仍为 %d，已发布事件 %d 个%n",
                    mode, error, count("SELECT confirmed_count FROM session WHERE id=1"), published.size());
        }
    }

    // ---------- 4. 只能经由根修改 ----------

    static void rootOnlyModification() {
        List<String> inside = new ArrayList<>(List.of("alice"));
        List<String> view = Collections.unmodifiableList(inside);
        List<String> copy = List.copyOf(inside);
        String viewResult, copyResult;
        try { view.add("mallory"); viewResult = "成功"; } catch (UnsupportedOperationException e) { viewResult = "UnsupportedOperationException"; }
        try { copy.add("mallory"); copyResult = "成功"; } catch (UnsupportedOperationException e) { copyResult = "UnsupportedOperationException"; }
        inside.add("bob"); // 根自己的修改
        System.out.printf("root.view\t向只读视图添加：%s；根修改后视图可见 %s，副本仍为 %s%n", viewResult, view, copy);
        System.out.printf("root.copy\t向 List.copyOf 副本添加：%s%n", copyResult);
    }

    // ---------- 工具 ----------

    static Connection conn() throws SQLException { return DriverManager.getConnection(URL, "root", "example_password"); }

    static int[] lockSession(Connection c, long sid) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT capacity, confirmed_count, waitlisted_count FROM session WHERE id=? FOR UPDATE")) {
            p.setLong(1, sid);
            ResultSet r = p.executeQuery(); r.next();
            return new int[] {r.getInt(1), r.getInt(2), r.getInt(3)};
        }
    }

    static void lockWaitlist(Connection c, long sid) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT session_id FROM waitlist WHERE session_id=? FOR UPDATE")) { p.setLong(1, sid); p.executeQuery().next(); }
    }

    static String firstWaiting(Connection c, long sid) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT attendee FROM registration WHERE session_id=? AND status='WAITLISTED' ORDER BY seq LIMIT 1 FOR UPDATE")) {
            p.setLong(1, sid);
            ResultSet r = p.executeQuery();
            return r.next() ? r.getString(1) : null;
        }
    }

    static void insert(Connection c, long sid, String who, String status) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO registration (session_id, attendee, status) VALUES (?, ?, ?)")) {
            p.setLong(1, sid); p.setString(2, who); p.setString(3, status); p.executeUpdate();
        }
    }

    static void update(Connection c, String sql, long sid) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) { p.setLong(1, sid); p.executeUpdate(); }
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) { s.execute(sql); }
    }

    static int count(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getInt(1); }
    }

    static long status(String name) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW GLOBAL STATUS LIKE '" + name + "'")) { r.next(); return r.getLong(2); }
    }

    static void reset() throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            for (String t : List.of("registration", "waitlist", "session", "event")) s.execute("TRUNCATE TABLE " + t);
        }
    }
}
