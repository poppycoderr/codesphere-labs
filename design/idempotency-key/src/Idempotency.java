import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 基于 MySQL 的幂等键：抢占、重放、请求不一致、失败处理、租约过期接管，以及业务写入与幂等记录分开提交或在同一事务中提交的差别。
 * 运行：java -cp <mysql-connector> src/Idempotency.java，连接 127.0.0.1:3306/labs。
 */
public class Idempotency {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true";

    enum Outcome { EXECUTED, REPLAYED, IN_PROGRESS, MISMATCH, FAILED, FENCED }

    enum Mode { SEPARATE, SAME_TRANSACTION }

    record Result(Outcome outcome, String response) {
    }

    /** 业务：写入一条报名。failure 为 "retryable" 或 "permanent" 时模拟下游超时与参数错误。 */
    record Business(String user, String activity, long workMillis, String failure, boolean crashBeforeMarking) {
    }

    record Claim(long token) {
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, "root", "example_password");
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class Crash extends RuntimeException {
        Crash() {
            super("进程在业务提交之后、更新幂等记录之前崩溃");
        }
    }

    /** 执行协议。返回 null 的 claim 表示没有取得执行权，Result 描述原因。 */
    static Result execute(String key, Business b, Mode mode, long leaseMillis, String executor) throws Exception {
        String reqHash = hash(b.user() + "|" + b.activity());
        Claim claim;
        try (Connection c = connect()) {
            claim = claim(c, key, reqHash, leaseMillis);
            if (claim == null) return inspect(c, key, reqHash);
        }
        if (b.workMillis() > 0) Thread.sleep(b.workMillis());
        if ("retryable".equals(b.failure())) {
            try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM idempotency_keys WHERE idem_key = ? AND token = ?")) {
                ps.setString(1, key);
                ps.setLong(2, claim.token());
                ps.executeUpdate();
            }
            return new Result(Outcome.FAILED, "下游超时，已释放幂等键");
        }
        if ("permanent".equals(b.failure())) {
            try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                    "UPDATE idempotency_keys SET status = 'FAILED', response = ? WHERE idem_key = ? AND token = ?")) {
                ps.setString(1, "400 同行人数不合法");
                ps.setString(2, key);
                ps.setLong(3, claim.token());
                ps.executeUpdate();
            }
            return new Result(Outcome.FAILED, "400 同行人数不合法");
        }
        String response = "201 registration for " + b.user();
        try (Connection c = connect()) {
            if (mode == Mode.SEPARATE) {
                insertRegistration(c, key, b, executor);
                if (b.crashBeforeMarking()) throw new Crash();
                try (PreparedStatement ps = c.prepareStatement("UPDATE idempotency_keys SET status = 'SUCCEEDED', response = ? WHERE idem_key = ?")) {
                    ps.setString(1, response);
                    ps.setString(2, key);
                    ps.executeUpdate();
                }
                return new Result(Outcome.EXECUTED, response);
            }
            c.setAutoCommit(false);
            try {
                insertRegistration(c, key, b, executor);
                int marked;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE idempotency_keys SET status = 'SUCCEEDED', response = ? WHERE idem_key = ? AND token = ? AND status = 'PROCESSING'")) {
                    ps.setString(1, response);
                    ps.setString(2, key);
                    ps.setLong(3, claim.token());
                    marked = ps.executeUpdate();
                }
                if (marked == 0) {
                    c.rollback();
                    return new Result(Outcome.FENCED, "执行权已被接管，回滚本次业务写入");
                }
                if (b.crashBeforeMarking()) {
                    c.rollback();
                    throw new Crash();
                }
                c.commit();
                return new Result(Outcome.EXECUTED, response);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    static void insertRegistration(Connection c, String key, Business b, String executor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO registrations (idem_key, user_id, activity, executor) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, b.user());
            ps.setString(3, b.activity());
            ps.setString(4, executor);
            ps.executeUpdate();
        }
    }

    /** 插入 PROCESSING 取得执行权；键已存在且租约过期（PROCESSING）时，用 token 做比较并交换接管。 */
    static Claim claim(Connection c, String key, String reqHash, long leaseMillis) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO idempotency_keys (idem_key, request_hash, status, lease_until, token) VALUES (?, ?, 'PROCESSING', NOW(3) + INTERVAL ? MICROSECOND, 1)")) {
            ps.setString(1, key);
            ps.setString(2, reqHash);
            ps.setLong(3, leaseMillis * 1000);
            ps.executeUpdate();
            return new Claim(1);
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            // 键已存在，继续判断能否接管
        }
        long token;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT token FROM idempotency_keys WHERE idem_key = ? AND request_hash = ? AND status = 'PROCESSING' AND lease_until < NOW(3)")) {
            ps.setString(1, key);
            ps.setString(2, reqHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                token = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE idempotency_keys SET token = token + 1, lease_until = NOW(3) + INTERVAL ? MICROSECOND WHERE idem_key = ? AND token = ?")) {
            ps.setLong(1, leaseMillis * 1000);
            ps.setString(2, key);
            ps.setLong(3, token);
            return ps.executeUpdate() == 1 ? new Claim(token + 1) : null;
        }
    }

    static Result inspect(Connection c, String key, String reqHash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT request_hash, status, response FROM idempotency_keys WHERE idem_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new Result(Outcome.IN_PROGRESS, "键刚被释放，稍后重试");
                if (!rs.getString(1).equals(reqHash)) return new Result(Outcome.MISMATCH, "422 同一个幂等键对应了不同的请求");
                return switch (rs.getString(2)) {
                    case "SUCCEEDED", "FAILED" -> new Result(Outcome.REPLAYED, rs.getString(3));
                    default -> new Result(Outcome.IN_PROGRESS, "409 请求正在处理");
                };
            }
        }
    }

    static int rows(String key) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM registrations WHERE idem_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static void out(String key, Object value) {
        System.out.println(key + "\t" + value);
    }

    public static void main(String[] args) throws Exception {
        concurrentSameKey();
        replayAndMismatch();
        failures();
        leaseExpiry();
        crashWindow();
    }

    static void concurrentSameKey() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            fs.add(pool.submit(() -> {
                go.await();
                return execute("k-concurrent", new Business("alice", "java-meetup", 300, null, false), Mode.SAME_TRANSACTION, 5000, "t" + n);
            }));
        }
        go.countDown();
        Map<Outcome, Integer> counts = new TreeMap<>();
        for (Future<Result> f : fs) counts.merge(f.get().outcome(), 1, Integer::sum);
        pool.shutdown();
        out("concurrent.same_key", "20 个并发请求：" + counts + "；报名写入 " + rows("k-concurrent") + " 条");
    }

    static void replayAndMismatch() throws Exception {
        Map<Outcome, Integer> counts = new TreeMap<>();
        String response = null;
        for (int i = 0; i < 5; i++) {
            Result r = execute("k-concurrent", new Business("alice", "java-meetup", 0, null, false), Mode.SAME_TRANSACTION, 5000, "replay");
            counts.merge(r.outcome(), 1, Integer::sum);
            response = r.response();
        }
        out("replay", "完成后再发 5 次：" + counts + "，返回「" + response + "」；报名仍为 " + rows("k-concurrent") + " 条");
        Result m = execute("k-concurrent", new Business("alice", "kotlin-meetup", 0, null, false), Mode.SAME_TRANSACTION, 5000, "other");
        out("mismatch", "同一个键、不同的请求内容：" + m.outcome() + "「" + m.response() + "」");
    }

    static void failures() throws Exception {
        Result r1 = execute("k-retryable", new Business("bob", "java-meetup", 0, "retryable", false), Mode.SAME_TRANSACTION, 5000, "a");
        Result r2 = execute("k-retryable", new Business("bob", "java-meetup", 0, null, false), Mode.SAME_TRANSACTION, 5000, "b");
        out("failure.retryable", "第一次 " + r1.outcome() + "（" + r1.response() + "）；重试 " + r2.outcome() + "；报名 " + rows("k-retryable") + " 条");
        Result p1 = execute("k-permanent", new Business("carol", "java-meetup", 0, "permanent", false), Mode.SAME_TRANSACTION, 5000, "a");
        Result p2 = execute("k-permanent", new Business("carol", "java-meetup", 0, null, false), Mode.SAME_TRANSACTION, 5000, "b");
        out("failure.permanent", "第一次 " + p1.outcome() + "（" + p1.response() + "）；同一个键再发 " + p2.outcome() + "（" + p2.response() + "）；报名 " + rows("k-permanent") + " 条");
    }

    /** 租约 500ms；执行者 A 的业务耗时 1500ms，B 在第 700ms 重试并接管。 */
    static void leaseExpiry() throws Exception {
        for (Mode mode : Mode.values()) {
            String key = "k-lease-" + mode;
            Map<String, String> outcomes = new ConcurrentHashMap<>();
            Thread a = Thread.ofPlatform().start(() -> {
                try {
                    outcomes.put("A", execute(key, new Business("dave", "java-meetup", 1500, null, false), mode, 500, "A").outcome().name());
                } catch (Exception e) {
                    outcomes.put("A", e.toString());
                }
            });
            Thread.sleep(700);
            outcomes.put("B", execute(key, new Business("dave", "java-meetup", 0, null, false), mode, 500, "B").outcome().name());
            a.join();
            out("lease." + mode, "A（慢）→ " + outcomes.get("A") + "，B（接管）→ " + outcomes.get("B") + "；报名 " + rows(key) + " 条");
        }
    }

    /** 执行者在业务写入之后、标记成功之前崩溃；租约过期后客户端重试。 */
    static void crashWindow() throws Exception {
        for (Mode mode : Mode.values()) {
            String key = "k-crash-" + mode;
            String first;
            try {
                execute(key, new Business("erin", "java-meetup", 0, null, true), mode, 300, "A");
                first = "未崩溃";
            } catch (Crash e) {
                first = "崩溃";
            }
            Thread.sleep(400);
            Result retry = execute(key, new Business("erin", "java-meetup", 0, null, false), mode, 300, "B");
            out("crash." + mode, "第一次 " + first + "；租约过期后重试 " + retry.outcome() + "；报名 " + rows(key) + " 条");
        }
    }
}
