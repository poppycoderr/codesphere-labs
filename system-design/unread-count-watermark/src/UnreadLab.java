import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 未读数的两种存法：每来一条消息给接收方的计数器加一，或者只记「读到哪一条」、未读数现算。
 * 用两条数据库连接按固定顺序交替执行，构造重复投递、清零与新消息交错、多端上报乱序、事务提交顺序与自增 ID 顺序不一致这几种情况。
 */
public class UnreadLab {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static Connection open() throws SQLException { return DriverManager.getConnection(URL, "root", "example_password"); }

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
    static long insertMessage(Connection c, long conv, String sender) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO messages (conv_id, sender, body) VALUES (?, ?, 'hi')", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, conv); ps.setString(2, sender); ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }
    static long unreadByWatermark(Connection c, String user, long conv) throws SQLException {
        return one(c, "SELECT COUNT(*) FROM messages m WHERE m.conv_id = ? AND m.sender <> ? AND m.id > (SELECT last_read_id FROM read_watermark WHERE user_id = ? AND conv_id = ?)", conv, user, user, conv);
    }

    public static void main(String[] args) throws Exception {
        try (Connection a = open(); Connection b = open()) {
            out("env", "java.version=" + System.getProperty("java.version") + " mysql=" + a.getMetaData().getDatabaseProductVersion() + " isolation=" + (a.getTransactionIsolation() == Connection.TRANSACTION_REPEATABLE_READ ? "REPEATABLE-READ" : "other"));
            for (String ddl : new String[]{
                    "DROP TABLE IF EXISTS messages, unread_counter, read_watermark, conversations, seq_messages",
                    "CREATE TABLE messages (id BIGINT AUTO_INCREMENT PRIMARY KEY, conv_id BIGINT NOT NULL, sender VARCHAR(32) NOT NULL, body VARCHAR(64) NOT NULL, KEY idx_conv (conv_id, id))",
                    "CREATE TABLE unread_counter (user_id VARCHAR(32), conv_id BIGINT, unread INT NOT NULL, PRIMARY KEY (user_id, conv_id))",
                    "CREATE TABLE read_watermark (user_id VARCHAR(32), conv_id BIGINT, last_read_id BIGINT NOT NULL, PRIMARY KEY (user_id, conv_id))",
                    "CREATE TABLE conversations (conv_id BIGINT PRIMARY KEY, last_seq BIGINT NOT NULL)",
                    "CREATE TABLE seq_messages (conv_id BIGINT, seq BIGINT, sender VARCHAR(32) NOT NULL, PRIMARY KEY (conv_id, seq))"}) exec(a, ddl);

            // 一、同一条消息的通知被投递了两次
            exec(a, "INSERT INTO unread_counter VALUES ('bob', 1, 0)"); exec(a, "INSERT INTO read_watermark VALUES ('bob', 1, 0)");
            for (int i = 1; i <= 10; i++) {
                insertMessage(a, 1, "alice");
                int deliveries = (i % 3 == 0) ? 2 : 1;                         // 第 3、6、9 条的「新消息」事件被重复投递
                for (int k = 0; k < deliveries; k++) exec(a, "UPDATE unread_counter SET unread = unread + 1 WHERE user_id = 'bob' AND conv_id = 1");
            }
            out("retry.counter", "alice 发了 10 条，其中 3 条的通知被重复投递：计数器 = " + one(a, "SELECT unread FROM unread_counter WHERE user_id = 'bob' AND conv_id = 1"));
            out("retry.watermark", "同样的投递，按已读位置现算：未读 = " + unreadByWatermark(a, "bob", 1));

            // 二、清零与新消息交错
            long seen = one(a, "SELECT MAX(id) FROM messages WHERE conv_id = 1");        // bob 的客户端拉到了这里，准备上报「已读」
            exec(a, "UPDATE unread_counter SET unread = 0 WHERE user_id = 'bob' AND conv_id = 1");
            exec(a, "UPDATE unread_counter SET unread = 10 WHERE user_id = 'bob' AND conv_id = 1"); // 复位成与真实未读一致，再演示交错
            insertMessage(a, 1, "alice");                                                // 第 11 条在「已读」请求到达之前写入
            exec(a, "UPDATE unread_counter SET unread = unread + 1 WHERE user_id = 'bob' AND conv_id = 1");
            exec(a, "UPDATE unread_counter SET unread = 0 WHERE user_id = 'bob' AND conv_id = 1");           // 「已读」请求到达：清零
            exec(a, "UPDATE read_watermark SET last_read_id = ? WHERE user_id = 'bob' AND conv_id = 1", seen); // 水位方式：上报看到的最后一条
            out("clear_race.counter", "bob 看完前 10 条后上报已读，请求到达前第 11 条已写入：计数器清零后 = " + one(a, "SELECT unread FROM unread_counter WHERE user_id = 'bob' AND conv_id = 1") + "，实际没看过的有 1 条");
            out("clear_race.watermark", "上报的是「读到第 " + seen + " 条」：未读 = " + unreadByWatermark(a, "bob", 1));

            // 三、多端上报乱序
            for (int i = 0; i < 9; i++) insertMessage(a, 1, "alice");                    // 现在共 20 条
            long latest = one(a, "SELECT MAX(id) FROM messages WHERE conv_id = 1");
            exec(a, "UPDATE read_watermark SET last_read_id = ? WHERE user_id = 'bob' AND conv_id = 1", latest);   // 手机读到第 20 条
            exec(a, "UPDATE read_watermark SET last_read_id = ? WHERE user_id = 'bob' AND conv_id = 1", 12L);      // 电脑上一个迟到的请求：读到第 12 条
            out("devices.overwrite", "手机上报读到第 20 条，随后电脑上一个迟到的请求上报第 12 条，直接覆盖：未读 = " + unreadByWatermark(a, "bob", 1));
            exec(a, "UPDATE read_watermark SET last_read_id = ? WHERE user_id = 'bob' AND conv_id = 1", latest);
            exec(a, "UPDATE read_watermark SET last_read_id = GREATEST(last_read_id, ?) WHERE user_id = 'bob' AND conv_id = 1", 12L);
            out("devices.greatest", "写成 last_read_id = GREATEST(last_read_id, ?)：未读 = " + unreadByWatermark(a, "bob", 1));

            // 四、自己发的消息
            exec(a, "INSERT INTO read_watermark VALUES ('alice', 1, 0)");
            out("own_messages", "alice 自己发了 20 条、从没上报过已读：不排除自己发的 = " + one(a, "SELECT COUNT(*) FROM messages WHERE conv_id = 1 AND id > 0") + "，排除后 = " + unreadByWatermark(a, "alice", 1));

            // 五、自增 ID 的顺序不是提交顺序
            exec(a, "INSERT INTO read_watermark VALUES ('carol', 2, 0)");
            a.setAutoCommit(false); b.setAutoCommit(false);
            long idA = insertMessage(a, 2, "alice");                                     // 事务 A 先插入，拿到较小的 ID，但还没提交
            long idB = insertMessage(b, 2, "dave"); b.commit();                          // 事务 B 后插入、先提交
            try (Connection reader = open()) {
                long maxSeen = one(reader, "SELECT MAX(id) FROM messages WHERE conv_id = 2");
                exec(reader, "UPDATE read_watermark SET last_read_id = ? WHERE user_id = 'carol' AND conv_id = 2", maxSeen);
                a.commit();                                                              // A 现在才提交，它的 ID 比已读位置小
                out("commit_order.ids", "事务 A 先插入（ID 排第 1）未提交，事务 B 后插入（ID 排第 2）先提交；A 的 ID 小于 B 的 ID = " + (idA < idB));
                out("commit_order.missed", "carol 此时拉取，只看到 B 的那条，已读位置记到 B 的 ID；A 提交之后：未读 = " + unreadByWatermark(reader, "carol", 2)
                        + "，而会话里 ID 不大于已读位置的消息有 " + one(reader, "SELECT COUNT(*) FROM messages WHERE conv_id = 2 AND id <= ?", maxSeen) + " 条，carol 只看过 1 条");
            }
            long rolledBack = insertMessage(a, 2, "alice"); a.rollback();
            long next = insertMessage(b, 2, "dave"); b.commit();
            out("commit_order.gap", "一个事务插入后回滚，下一条消息的 ID 与上一条已提交消息的 ID 相差 " + (next - idB) + "（回滚的那个 ID 是其间的 " + (rolledBack - idB) + " 号位，不再出现）");

            // 六、按会话加锁分配序号：序号顺序就是提交顺序
            exec(a, "INSERT INTO conversations VALUES (3, 0)"); a.commit();
            long seqA = one(a, "SELECT last_seq FROM conversations WHERE conv_id = 3 FOR UPDATE") + 1;        // A 锁住会话行
            exec(a, "UPDATE conversations SET last_seq = ? WHERE conv_id = 3", seqA);
            exec(a, "INSERT INTO seq_messages VALUES (3, ?, 'alice')", seqA);
            exec(b, "SET SESSION innodb_lock_wait_timeout = 1");
            String blocked;
            try { one(b, "SELECT last_seq FROM conversations WHERE conv_id = 3 FOR UPDATE"); blocked = "没有被挡住"; }
            catch (SQLException e) { blocked = "等待 1 秒后超时（" + e.getMessage() + "）"; b.rollback(); }
            a.commit();
            long seqB = one(b, "SELECT last_seq FROM conversations WHERE conv_id = 3 FOR UPDATE") + 1;
            exec(b, "UPDATE conversations SET last_seq = ? WHERE conv_id = 3", seqB);
            exec(b, "INSERT INTO seq_messages VALUES (3, ?, 'dave')", seqB); b.commit();
            out("conv_seq.blocked", "事务 A 锁住会话行分配序号、尚未提交时，事务 B 来取序号：" + blocked);
            out("conv_seq.order", "A 提交后 B 重试：A 的序号 " + seqA + "，B 的序号 " + seqB + "；序号小的一定先提交");
            a.setAutoCommit(true); b.setAutoCommit(true);

            // 七、群聊：每条消息要写多少行
            StringBuilder values = new StringBuilder();
            for (int i = 0; i < 1000; i++) values.append(i == 0 ? "" : ",").append("('m").append(i).append("', 9, 0)");
            exec(a, "INSERT INTO unread_counter VALUES " + values);
            int touched = exec(a, "UPDATE unread_counter SET unread = unread + 1 WHERE conv_id = 9 AND user_id <> 'm0'");
            out("fanout.counter", "1000 人的群里发一条消息，计数器方式要更新 " + touched + " 行；已读位置方式要更新 0 行（未读数在读的时候算）");
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 5000; i++) many.append(i == 0 ? "" : ",").append("(9, 'm0', 'hi')");
            exec(a, "INSERT INTO messages (conv_id, sender, body) VALUES " + many);
            exec(a, "INSERT INTO read_watermark VALUES ('m1', 9, 0)");
            out("fanout.capped", "m1 有 5000 条未读：完整计数 = " + unreadByWatermark(a, "m1", 9) + "；只数到 100 就停（界面显示 99+）= "
                    + one(a, "SELECT COUNT(*) FROM (SELECT 1 FROM messages WHERE conv_id = 9 AND sender <> 'm1' AND id > 0 LIMIT 100) t"));
        }
    }
}
