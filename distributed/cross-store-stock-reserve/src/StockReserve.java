import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 库存先在 Redis 预扣、再到 MySQL 扣减：朴素写法的三个漏洞，与「预占记录 + 数据库为准 + 对账」的修正写法。
 * 每个场景结束后检查两边是否一致：初始库存 = 数据库可用 + 数据库已售，Redis 库存 = 数据库可用，且没有遗留的预占。
 */
public class StockReserve {

    static final String URL = "jdbc:mysql://mysql:3306/labs?useSSL=false&allowPublicKeyRetrieval=true";
    static final int INITIAL = 10;

    static final class RedisError extends IOException {
        RedisError(String message) {
            super(message);
        }
    }

    /** 最小的 RESP2 客户端：一条连接，一次一条命令。 */
    static final class Resp implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Resp(String host, int port, int timeoutMillis) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        Object call(Object... args) throws IOException {
            send(args);
            return read();
        }

        void send(Object... args) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
            for (Object a : args) {
                String s = String.valueOf(a);
                sb.append('$').append(s.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(s).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        Object read() throws IOException {
            int type = in.read();
            if (type < 0) throw new IOException("连接被关闭");
            String line = line();
            return switch (type) {
                case '+' -> line;
                case '-' -> throw new RedisError(line);
                case ':' -> Long.parseLong(line);
                case '$' -> {
                    int n = Integer.parseInt(line);
                    if (n < 0) yield null;
                    byte[] b = in.readNBytes(n + 2);
                    yield new String(b, 0, n, StandardCharsets.UTF_8);
                }
                case '*' -> {
                    int n = Integer.parseInt(line);
                    if (n < 0) yield null;
                    List<Object> items = new ArrayList<>();
                    for (int i = 0; i < n; i++) items.add(read());
                    yield items;
                }
                default -> throw new IOException("未知回复类型 " + (char) type);
            };
        }

        void noTimeout() throws IOException {
            socket.setSoTimeout(0);
        }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != '\r') {
                if (c < 0) throw new IOException("连接被关闭");
                sb.append((char) c);
            }
            in.read();
            return sb.toString();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败不影响实验
            }
        }
    }



    static Connection db() throws Exception {
        return DriverManager.getConnection(URL, "root", "example_password");
    }

    static Resp redis() throws IOException {
        return new Resp("redis", 6379, 5000);
    }

    // 预占：同一订单只预占一次；库存不足返回 0
    static final String RESERVE = """
            if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
            local s = tonumber(redis.call('GET', KEYS[1]))
            local q = tonumber(ARGV[1])
            if s < q then return 0 end
            redis.call('DECRBY', KEYS[1], q)
            redis.call('SET', KEYS[2], q)
            return 1""";
    // 释放预占：预占还在才加回，重复调用无效果
    static final String RELEASE = """
            local q = redis.call('GET', KEYS[2])
            if not q then return 0 end
            redis.call('INCRBY', KEYS[1], q)
            redis.call('DEL', KEYS[2])
            return 1""";
    // 已售订单取消后加回：每个订单只加回一次
    static final String CANCEL_RETURN = """
            if redis.call('SET', KEYS[2], 1, 'NX') then
              redis.call('INCRBY', KEYS[1], ARGV[1])
              return 1
            end
            return 0""";

    static Object eval(Resp r, String script, String k1, String k2, Object arg) throws IOException {
        return r.call("EVAL", script, 2, k1, k2, arg);
    }

    static void reset() throws Exception {
        try (Connection c = db(); Statement s = c.createStatement(); Resp r = redis()) {
            s.execute("DROP TABLE IF EXISTS stock_log");
            s.execute("DROP TABLE IF EXISTS inventory");
            s.execute("CREATE TABLE inventory (sku VARCHAR(10) PRIMARY KEY, available INT NOT NULL, sold INT NOT NULL, on_sale TINYINT NOT NULL)");
            s.execute("CREATE TABLE stock_log (order_id VARCHAR(20) PRIMARY KEY, sku VARCHAR(10) NOT NULL, qty INT NOT NULL,"
                    + " state VARCHAR(10) NOT NULL)");
            s.execute("INSERT INTO inventory VALUES ('sku1', " + INITIAL + ", 0, 1)");
            r.call("FLUSHALL");
            r.call("SET", "stock:sku1", INITIAL);
        }
    }

    /** 当前状态与守恒检查的一行描述。 */
    static String state() throws Exception {
        try (Connection c = db(); Statement s = c.createStatement(); Resp r = redis()) {
            ResultSet rs = s.executeQuery("SELECT available, sold FROM inventory WHERE sku = 'sku1'");
            rs.next();
            int avail = rs.getInt(1);
            int sold = rs.getInt(2);
            long redisStock = Long.parseLong((String) r.call("GET", "stock:sku1"));
            int holds = ((List<?>) r.call("KEYS", "hold:*")).size();
            boolean ok = avail + sold == INITIAL && redisStock == avail && holds == 0;
            return "Redis " + redisStock + "，数据库可用 " + avail + "、已售 " + sold + "，遗留预占 " + holds + "，两边一致=" + (ok ? "True" : "False");
        }
    }

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static void setOnSale(int v) throws Exception {
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute("UPDATE inventory SET on_sale = " + v);
        }
    }

    static long connectionId(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 模拟提交前连接断开：从另一条连接 KILL 它。 */
    static void killLater(Connection victim) throws Exception {
        long id = connectionId(victim);
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute("KILL " + id);
        }
    }

    // ---------- 朴素写法 ----------

    /** 朴素下单：Redis 扣减，数据库扣减；数据库返回 0 行时直接返回 false，提交由调用方完成。 */
    static boolean naivePlace(Connection c, Resp r, String orderId) throws Exception {
        long left = (Long) r.call("DECRBY", "stock:sku1", 1);
        if (left < 0) {
            r.call("INCRBY", "stock:sku1", 1);
            return false;
        }
        try {
            PreparedStatement u = c.prepareStatement("UPDATE inventory SET available = available - 1, sold = sold + 1"
                    + " WHERE sku = 'sku1' AND available >= 1 AND on_sale = 1");
            if (u.executeUpdate() == 0) {
                return false;                                   // 漏洞一：没有把 Redis 加回去
            }
            PreparedStatement l = c.prepareStatement("INSERT INTO stock_log VALUES (?, 'sku1', 1, 'CONFIRMED')");
            l.setString(1, orderId);
            l.executeUpdate();
            return true;
        } catch (SQLException e) {
            r.call("INCRBY", "stock:sku1", 1);
            throw e;
        }
    }

    /** 朴素取消：先把 Redis 加回，再恢复数据库；失败后整体重试。 */
    static void naiveCancel(Resp r, String orderId, boolean failDbFirstTime, AtomicInteger attempts) throws Exception {
        r.call("INCRBY", "stock:sku1", 1);
        try (Connection c = db()) {
            c.setAutoCommit(false);
            PreparedStatement u = c.prepareStatement("UPDATE stock_log SET state = 'CANCELLED' WHERE order_id = ? AND state = 'CONFIRMED'");
            u.setString(1, orderId);
            u.executeUpdate();
            c.prepareStatement("UPDATE inventory SET available = available + 1, sold = sold - 1 WHERE sku = 'sku1'").executeUpdate();
            if (failDbFirstTime && attempts.getAndIncrement() == 0) {
                killLater(c);
            }
            c.commit();
        }
    }

    static void naive() throws Exception {
        // 漏洞一：数据库条件不满足（商品已下架）返回 false，Redis 没有加回
        reset();
        setOnSale(0);
        try (Connection c = db(); Resp r = redis()) {
            c.setAutoCommit(false);
            boolean ok = naivePlace(c, r, "n1");
            c.commit();
            setOnSale(1);
            out("naive.db-false", "数据库扣减 0 行、方法返回 " + ok + "：" + state());
        }
        // 漏洞二：方法正常返回，调用方提交时连接断开；方法里的 catch 捕获不到
        reset();
        try (Connection c = db(); Resp r = redis()) {
            c.setAutoCommit(false);
            boolean ok = naivePlace(c, r, "n2");
            killLater(c);
            String err = "无";
            try {
                c.commit();
            } catch (SQLException e) {
                err = "提交失败";
            }
            out("naive.commit-fail", "方法返回 " + ok + "，" + err + "：" + state());
        } catch (SQLException ignored) {
            // 连接已被 KILL，关闭时的异常不影响结果
        }
        // 漏洞三：取消先加回 Redis，数据库提交失败后整体重试，Redis 加了两次
        reset();
        try (Connection c = db(); Resp r = redis()) {
            c.setAutoCommit(false);
            naivePlace(c, r, "n3");
            c.commit();
            AtomicInteger attempts = new AtomicInteger();
            for (int i = 0; i < 3; i++) {
                try {
                    naiveCancel(r, "n3", true, attempts);
                    break;
                } catch (SQLException e) {
                    // 重试
                }
            }
            out("naive.cancel-retry", "取消时数据库第一次提交失败、重试成功：" + state());
        }
    }

    // ---------- 修正写法 ----------

    enum Crash { NONE, AFTER_RESERVE, BEFORE_COMMIT, AFTER_COMMIT, CANCEL_AFTER_DB }

    static final class CrashException extends RuntimeException {
        CrashException(String where) {
            super(where);
        }
    }

    /**
     * 修正下单：Redis 预占（带订单号、幂等）→ 数据库扣减并写流水（同一事务）→ 提交 → 删除预占。
     * 数据库明确拒绝（0 行）时立即释放预占；提交失败时结果未知，不释放，交给对账按数据库流水判定。
     */
    static String place(String orderId, Crash crash) throws Exception {
        try (Resp r = redis()) {
            long res = (Long) eval(r, RESERVE, "stock:sku1", "hold:" + orderId, 1);
            if (res == 0) {
                return "库存不足";
            }
            if (crash == Crash.AFTER_RESERVE) {
                throw new CrashException("预占之后");
            }
            try (Connection c = db()) {
                c.setAutoCommit(false);
                PreparedStatement u = c.prepareStatement("UPDATE inventory SET available = available - 1, sold = sold + 1"
                        + " WHERE sku = 'sku1' AND available >= 1 AND on_sale = 1");
                if (u.executeUpdate() == 0) {
                    c.rollback();
                    eval(r, RELEASE, "stock:sku1", "hold:" + orderId, 0);
                    return "数据库拒绝，已释放预占";
                }
                PreparedStatement l = c.prepareStatement("INSERT INTO stock_log VALUES (?, 'sku1', 1, 'CONFIRMED')");
                l.setString(1, orderId);
                l.executeUpdate();
                if (crash == Crash.BEFORE_COMMIT) {
                    killLater(c);
                }
                try {
                    c.commit();
                } catch (SQLException e) {
                    return "提交结果未知，保留预占";
                }
            }
            if (crash == Crash.AFTER_COMMIT) {
                throw new CrashException("提交之后、删除预占之前");
            }
            r.call("DEL", "hold:" + orderId);
            return "成功";
        }
    }

    /** 修正取消：先在数据库里把流水从 CONFIRMED 改成 CANCELLED（条件更新，只成功一次），再按订单号在 Redis 加回一次。 */
    static void cancel(String orderId, Crash crash) throws Exception {
        try (Connection c = db()) {
            c.setAutoCommit(false);
            PreparedStatement u = c.prepareStatement("UPDATE stock_log SET state = 'CANCELLED' WHERE order_id = ? AND state = 'CONFIRMED'");
            u.setString(1, orderId);
            if (u.executeUpdate() == 1) {
                c.prepareStatement("UPDATE inventory SET available = available + 1, sold = sold - 1 WHERE sku = 'sku1'").executeUpdate();
            }
            c.commit();
        }
        if (crash == Crash.CANCEL_AFTER_DB) {
            throw new CrashException("取消：数据库之后、Redis 之前");
        }
        try (Resp r = redis()) {
            eval(r, CANCEL_RETURN, "stock:sku1", "returned:" + orderId, 1);
        }
    }

    /**
     * 对账：遗留的预占按数据库流水判定——有流水说明扣减已提交，删除预占；没有流水说明没扣成，释放预占。
     * 已取消但 Redis 还没加回的订单，补一次加回（同样按订单号幂等）。实际系统只处理超过一定时长的预占，避免误判进行中的请求。
     */
    static int reconcile() throws Exception {
        int fixed = 0;
        try (Connection c = db(); Resp r = redis()) {
            for (Object k : (List<?>) r.call("KEYS", "hold:*")) {
                String orderId = ((String) k).substring(5);
                PreparedStatement q = c.prepareStatement("SELECT 1 FROM stock_log WHERE order_id = ?");
                q.setString(1, orderId);
                if (q.executeQuery().next()) {
                    r.call("DEL", k);
                } else {
                    eval(r, RELEASE, "stock:sku1", (String) k, 0);
                }
                fixed++;
            }
            ResultSet rs = c.createStatement().executeQuery("SELECT order_id, qty FROM stock_log WHERE state = 'CANCELLED'");
            while (rs.next()) {
                fixed += ((Long) eval(r, CANCEL_RETURN, "stock:sku1", "returned:" + rs.getString(1), rs.getInt(2))).intValue();
            }
        }
        return fixed;
    }

    static void fixed() throws Exception {
        reset();
        setOnSale(0);
        String res = place("f1", Crash.NONE);
        setOnSale(1);
        out("fixed.db-false", "数据库拒绝：" + res + "；" + state());

        reset();
        res = place("f2", Crash.BEFORE_COMMIT);
        String before = state();
        int n = reconcile();
        out("fixed.commit-fail", "提交时连接断开：" + res + "；对账前 " + before + "；对账处理 " + n + " 项后 " + state());

        reset();
        place("f3", Crash.NONE);
        cancel("f3", Crash.NONE);
        cancel("f3", Crash.NONE);
        out("fixed.cancel-twice", "同一订单取消两次：" + state());

        for (Crash crash : new Crash[] {Crash.AFTER_RESERVE, Crash.AFTER_COMMIT, Crash.CANCEL_AFTER_DB}) {
            reset();
            try {
                place("c1", crash == Crash.CANCEL_AFTER_DB ? Crash.NONE : crash);
                if (crash == Crash.CANCEL_AFTER_DB) {
                    cancel("c1", crash);
                }
            } catch (CrashException e) {
                // 进程在这里崩溃
            }
            String b = state();
            int k = reconcile();
            int again = reconcile();
            out("crash." + crash.name().toLowerCase().replace('_', '-'),
                    "崩溃点 " + crash + "：崩溃后 " + b + "；对账处理 " + k + " 项后 " + state() + "；再对账一次处理 " + again + " 项");
        }

        // 并发：50 个订单抢 10 件库存
        reset();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String id = "p" + i;
            fs.add(pool.submit(() -> place(id, Crash.NONE)));
        }
        int ok = 0;
        for (Future<String> f : fs) {
            if (f.get().equals("成功")) {
                ok++;
            }
        }
        pool.shutdown();
        out("fixed.concurrent", "50 个订单并发抢 10 件：成功 " + ok + " 个；" + state());
    }

    public static void main(String[] args) throws Exception {
        naive();
        fixed();
    }
}
