import com.google.gson.*;
import java.sql.*;
import java.util.*;

/**
 * 报名上下文 → 计费上下文的集成，输出为「键<TAB>事实」：
 * 1. 双写与 outbox：提交后、发送前崩溃；2. 转发器在投递后、标记前崩溃，消费端有无去重；
 * 3. 取消先于确认到达，消费端有无版本判断；4. 毒消息；5. 契约：新增字段与改名字段。
 * 传输用进程内调用模拟，只关心两端各自的事务与重试语义。
 */
public class Integration {
    static final String REG = "jdbc:mysql://127.0.0.1:3306/reg?useSSL=false&allowPublicKeyRetrieval=true";
    static final String BILLING = "jdbc:mysql://127.0.0.1:3306/billing?useSSL=false&allowPublicKeyRetrieval=true";

    public static void main(String[] args) throws Exception {
        dualWriteVersusOutbox();
        relayCrashAndDuplicates();
        outOfOrder();
        poisonMessage();
        contract();
    }

    // ---------- 报名上下文：业务写入与 outbox 同一事务 ----------

    /** 对外发布的集成事件：稳定、带版本号，不序列化内部的聚合对象。 */
    static String registrationConfirmedV1(String eventId, String registrationId, String attendee, int feeCents, int version) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", "registration.confirmed/v1");
        o.addProperty("eventId", eventId);
        o.addProperty("registrationId", registrationId);
        o.addProperty("attendeeId", attendee);
        o.addProperty("feeCents", feeCents);
        o.addProperty("currency", "CNY");
        o.addProperty("registrationVersion", version);
        return o.toString();
    }

    static String registrationCancelledV1(String eventId, String registrationId, int version) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", "registration.cancelled/v1");
        o.addProperty("eventId", eventId);
        o.addProperty("registrationId", registrationId);
        o.addProperty("registrationVersion", version);
        return o.toString();
    }

    static final class CrashBeforePublish extends RuntimeException {}

    /** 报名并在同一事务写 outbox；failBeforeCommit 模拟提交前失败。返回报名 id。 */
    static String register(String attendee, int feeCents, boolean withOutbox, boolean failBeforeCommit) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (Connection c = DriverManager.getConnection(REG, "root", "example_password")) {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("INSERT INTO registration VALUES (?, 'S-1', ?, ?, 'CONFIRMED', 1)")) {
                p.setString(1, id); p.setString(2, attendee); p.setInt(3, feeCents); p.executeUpdate();
            }
            if (withOutbox) {
                String eventId = UUID.randomUUID().toString();
                outbox(c, eventId, id, 1, "registration.confirmed/v1", registrationConfirmedV1(eventId, id, attendee, feeCents, 1));
            }
            if (failBeforeCommit) { c.rollback(); return null; }
            c.commit();
        }
        return id;
    }

    static void cancel(String registrationId) throws SQLException {
        try (Connection c = DriverManager.getConnection(REG, "root", "example_password")) {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("UPDATE registration SET status='CANCELLED', version=version+1 WHERE id=?")) {
                p.setString(1, registrationId); p.executeUpdate();
            }
            String eventId = UUID.randomUUID().toString();
            outbox(c, eventId, registrationId, 2, "registration.cancelled/v1", registrationCancelledV1(eventId, registrationId, 2));
            c.commit();
        }
    }

    static void outbox(Connection c, String eventId, String aggregateId, int version, String type, String payload) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO outbox (event_id, aggregate_id, aggregate_version, type, payload) VALUES (?,?,?,?,?)")) {
            p.setString(1, eventId); p.setString(2, aggregateId); p.setInt(3, version); p.setString(4, type); p.setString(5, payload); p.executeUpdate();
        }
    }

    // ---------- 转发器：从 outbox 读取、投递、标记 ----------

    static final class RelayCrashed extends RuntimeException {}

    /**
     * 每批最多 10 条，先投递整批再一次性标记为 SENT。crashAfterBatch > 0 时在第 n 批投递后、标记前崩溃。
     * 投递失败的事件累计尝试次数，第 3 次失败后写入死信并标记 FAILED，不阻塞后面的事件。
     */
    static int relay(Consumer consumer, int crashAfterBatch, boolean newestFirst) throws SQLException {
        int batches = 0, delivered = 0;
        while (true) {
            List<String[]> batch = new ArrayList<>();
            try (Connection c = DriverManager.getConnection(REG, "root", "example_password"); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT event_id, type, payload FROM outbox WHERE status='PENDING' ORDER BY id " + (newestFirst ? "DESC" : "") + " LIMIT 10")) {
                while (r.next()) batch.add(new String[] {r.getString(1), r.getString(2), r.getString(3)});
            }
            if (batch.isEmpty()) return delivered;
            batches++;
            List<String> sent = new ArrayList<>();
            for (String[] e : batch) {
                try {
                    consumer.handle(e[2]);
                    sent.add(e[0]);
                    delivered++;
                } catch (RuntimeException ex) {
                    failed(e, ex, consumer);
                }
            }
            if (batches == crashAfterBatch) throw new RelayCrashed();
            try (Connection c = DriverManager.getConnection(REG, "root", "example_password");
                 PreparedStatement p = c.prepareStatement("UPDATE outbox SET status='SENT' WHERE event_id=?")) {
                for (String id : sent) { p.setString(1, id); p.executeUpdate(); }
            }
        }
    }

    static void failed(String[] e, RuntimeException ex, Consumer consumer) throws SQLException {
        try (Connection c = DriverManager.getConnection(REG, "root", "example_password")) {
            try (PreparedStatement p = c.prepareStatement("UPDATE outbox SET attempts=attempts+1 WHERE event_id=?")) { p.setString(1, e[0]); p.executeUpdate(); }
            int attempts;
            try (PreparedStatement p = c.prepareStatement("SELECT attempts FROM outbox WHERE event_id=?")) { p.setString(1, e[0]); ResultSet r = p.executeQuery(); r.next(); attempts = r.getInt(1); }
            if (attempts >= 3) {
                try (PreparedStatement p = c.prepareStatement("UPDATE outbox SET status='FAILED' WHERE event_id=?")) { p.setString(1, e[0]); p.executeUpdate(); }
                consumer.deadLetter(e[0], e[1], ex.getMessage());
            }
        }
    }

    // ---------- 计费上下文：防腐层 + 去重 + 版本判断 ----------

    static final class ContractViolation extends RuntimeException { ContractViolation(String m) { super(m); } }

    /** 计费上下文自己的模型：应收，付款人与金额。 */
    record Receivable(String registrationId, String payerId, int amountCents, String currency, int sourceVersion) {}

    /** 防腐层：只读取契约里约定的字段，缺字段或类型不对时明确失败；多出来的字段忽略。 */
    static Receivable translateConfirmed(JsonObject o) {
        return new Receivable(required(o, "registrationId").getAsString(), "payer:" + required(o, "attendeeId").getAsString(),
                requiredInt(o, "feeCents"), required(o, "currency").getAsString(), requiredInt(o, "registrationVersion"));
    }

    static JsonElement required(JsonObject o, String field) {
        if (!o.has(field) || o.get(field).isJsonNull()) throw new ContractViolation("缺少字段 " + field);
        return o.get(field);
    }

    static int requiredInt(JsonObject o, String field) {
        try { return required(o, field).getAsInt(); } catch (NumberFormatException e) { throw new ContractViolation("字段 " + field + " 不是整数：" + o.get(field)); }
    }

    static final class Consumer {
        final boolean dedupe, versioned;
        int handled, skippedDuplicates, ignoredStale;
        Consumer(boolean dedupe, boolean versioned) { this.dedupe = dedupe; this.versioned = versioned; }

        void handle(String payload) {
            JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
            try (Connection c = DriverManager.getConnection(BILLING, "root", "example_password")) {
                c.setAutoCommit(false);
                if (dedupe) {
                    try (PreparedStatement p = c.prepareStatement("INSERT IGNORE INTO inbox VALUES (?)")) {
                        p.setString(1, required(o, "eventId").getAsString());
                        if (p.executeUpdate() == 0) { c.rollback(); skippedDuplicates++; return; }
                    }
                }
                String schema = required(o, "schema").getAsString();
                if (schema.equals("registration.confirmed/v1")) confirmed(c, translateConfirmed(o));
                else if (schema.equals("registration.cancelled/v1")) cancelled(c, required(o, "registrationId").getAsString(), requiredInt(o, "registrationVersion"));
                else throw new ContractViolation("未知的事件类型 " + schema);
                c.commit();
                handled++;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        void confirmed(Connection c, Receivable r) throws SQLException {
            Integer current = currentVersion(c, r.registrationId());
            if (versioned && current != null && current >= r.sourceVersion()) { ignoredStale++; return; }
            String sql = dedupe || versioned
                    ? "INSERT INTO receivable VALUES (?,?,?,?,'OPEN',?) ON DUPLICATE KEY UPDATE registration_id=registration_id"
                    : "INSERT INTO receivable_log (registration_id, payer_id, amount_cents, currency, status, source_version) VALUES (?,?,?,?,'OPEN',?)";
            try (PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, r.registrationId()); p.setString(2, r.payerId()); p.setInt(3, r.amountCents()); p.setString(4, r.currency()); p.setInt(5, r.sourceVersion());
                p.executeUpdate();
            }
        }

        void cancelled(Connection c, String registrationId, int version) throws SQLException {
            Integer current = currentVersion(c, registrationId);
            if (current == null) {
                if (versioned) {
                    // 确认还没到：先记下「已作废」，之后到达的旧版本确认会被忽略
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO receivable VALUES (?, '', 0, 'CNY', 'VOID', ?)")) {
                        p.setString(1, registrationId); p.setInt(2, version); p.executeUpdate();
                    }
                }
                return;
            }
            if (versioned && current >= version) { ignoredStale++; return; }
            try (PreparedStatement p = c.prepareStatement("UPDATE " + (dedupe || versioned ? "receivable" : "receivable_log") + " SET status='VOID', source_version=? WHERE registration_id=?")) {
                p.setInt(1, version); p.setString(2, registrationId); p.executeUpdate();
            }
        }

        Integer currentVersion(Connection c, String registrationId) throws SQLException {
            try (PreparedStatement p = c.prepareStatement("SELECT source_version FROM " + (dedupe || versioned ? "receivable" : "receivable_log") + " WHERE registration_id=? LIMIT 1")) {
                p.setString(1, registrationId); ResultSet r = p.executeQuery();
                return r.next() ? r.getInt(1) : null;
            }
        }

        void deadLetter(String eventId, String type, String error) throws SQLException {
            try (Connection c = DriverManager.getConnection(BILLING, "root", "example_password");
                 PreparedStatement p = c.prepareStatement("INSERT INTO dead_letter VALUES (?,?,?)")) {
                p.setString(1, eventId); p.setString(2, type); p.setString(3, error); p.executeUpdate();
            }
        }
    }

    // ---------- 场景 ----------

    static void dualWriteVersusOutbox() throws Exception {
        reset();
        Consumer billing = new Consumer(true, true);
        int lost = 0;
        for (int i = 0; i < 100; i++) {
            String id = register("dual" + i, 19_900, false, false);
            if (i % 10 == 9) { lost++; continue; } // 提交之后、调用计费之前进程崩溃
            String eventId = UUID.randomUUID().toString();
            billing.handle(registrationConfirmedV1(eventId, id, "dual" + i, 19_900, 1));
        }
        int dualRegs = count(REG, "SELECT COUNT(*) FROM registration"), dualRecv = count(BILLING, "SELECT COUNT(*) FROM receivable");
        System.out.printf("dualwrite\t提交后直接调用计费，每 10 次在调用前崩溃 1 次：报名 %d 条，应收 %d 条%n", dualRegs, dualRecv);

        reset();
        billing = new Consumer(true, true);
        for (int i = 0; i < 100; i++) register("ob" + i, 19_900, true, false);
        String rolledBack = register("rollback", 19_900, true, true);
        int pendingBeforeRelay = count(REG, "SELECT COUNT(*) FROM outbox WHERE status='PENDING'");
        relay(billing, 0, false);
        System.out.printf("outbox\t报名与 outbox 同一事务，另有 1 笔在提交前失败（%s）：报名 %d 条，outbox 待发送 %d 条；转发器处理后应收 %d 条%n",
                rolledBack == null ? "已回滚" : "已提交", count(REG, "SELECT COUNT(*) FROM registration"), pendingBeforeRelay, count(BILLING, "SELECT COUNT(*) FROM receivable"));
    }

    static void relayCrashAndDuplicates() throws Exception {
        for (boolean dedupe : List.of(false, true)) {
            reset();
            for (int i = 0; i < 50; i++) register("r" + i, 19_900, true, false);
            Consumer billing = new Consumer(dedupe, false);
            String crash = "";
            try { relay(billing, 3, false); } catch (RelayCrashed e) { crash = "第 3 批投递后、标记前崩溃；"; }
            relay(billing, 0, false); // 重启后继续
            String table = dedupe ? "receivable" : "receivable_log";
            System.out.printf("relay.%s\t50 个事件，%s重启后继续：计费处理 %d 次，应收 %d 条，去重跳过 %d 次%n",
                    dedupe ? "inbox" : "no-inbox", crash, billing.handled + billing.skippedDuplicates, count(BILLING, "SELECT COUNT(*) FROM " + table), billing.skippedDuplicates);
        }
    }

    static void outOfOrder() throws Exception {
        for (boolean versioned : List.of(false, true)) {
            reset();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 20; i++) ids.add(register("o" + i, 19_900, true, false));
            for (String id : ids) cancel(id);
            Consumer billing = new Consumer(true, versioned);
            relay(billing, 0, true); // 投递顺序与产生顺序相反：取消先到
            String table = "receivable";
            System.out.printf("order.%s\t20 笔报名先确认后取消，取消事件先到达：应收 OPEN %d 条、VOID %d 条，忽略过期事件 %d 次%n",
                    versioned ? "versioned" : "naive", count(BILLING, "SELECT COUNT(*) FROM " + table + " WHERE status='OPEN'"),
                    count(BILLING, "SELECT COUNT(*) FROM " + table + " WHERE status='VOID'"), billing.ignoredStale);
        }
    }

    static void poisonMessage() throws Exception {
        reset();
        for (int i = 0; i < 30; i++) {
            if (i == 5) {
                try (Connection c = DriverManager.getConnection(REG, "root", "example_password")) {
                    String eventId = UUID.randomUUID().toString();
                    String bad = registrationConfirmedV1(eventId, UUID.randomUUID().toString(), "bad", 0, 1).replace("\"feeCents\":0", "\"feeCents\":\"19.9 元\"");
                    outbox(c, eventId, "bad", 1, "registration.confirmed/v1", bad);
                }
            }
            register("p" + i, 19_900, true, false);
        }
        Consumer billing = new Consumer(true, true);
        for (int round = 0; round < 3; round++) relay(billing, 0, false); // 转发器定时运行 3 次
        String error;
        try (Connection c = DriverManager.getConnection(BILLING, "root", "example_password"); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT error FROM dead_letter")) { r.next(); error = r.getString(1); }
        System.out.printf("poison\t31 个事件中 1 个金额字段写成字符串：应收 %d 条，死信 %d 条（%s），outbox FAILED %d 条、仍待发送 %d 条%n",
                count(BILLING, "SELECT COUNT(*) FROM receivable"), count(BILLING, "SELECT COUNT(*) FROM dead_letter"), error,
                count(REG, "SELECT COUNT(*) FROM outbox WHERE status='FAILED'"), count(REG, "SELECT COUNT(*) FROM outbox WHERE status='PENDING'"));
    }

    static void contract() {
        String v1 = registrationConfirmedV1("e1", "r1", "alice", 19_900, 1);
        JsonObject added = JsonParser.parseString(v1).getAsJsonObject();
        added.addProperty("channel", "wechat");
        JsonObject renamed = JsonParser.parseString(v1).getAsJsonObject();
        renamed.add("amountCents", renamed.remove("feeCents"));
        System.out.printf("contract.v1\t生产者当前的 v1 消息：%s%n", tryTranslate(JsonParser.parseString(v1).getAsJsonObject()));
        System.out.printf("contract.added\t生产者新增字段 channel：%s%n", tryTranslate(added));
        System.out.printf("contract.renamed\t生产者把 feeCents 改名为 amountCents：%s%n", tryTranslate(renamed));
    }

    static String tryTranslate(JsonObject o) {
        try { Receivable r = translateConfirmed(o); return "计费侧翻译通过，应收 " + r.amountCents() + " 分，付款人 " + r.payerId(); }
        catch (ContractViolation e) { return "契约测试失败：" + e.getMessage(); }
    }

    // ---------- 工具 ----------

    static void reset() throws SQLException {
        try (Connection c = DriverManager.getConnection(REG, "root", "example_password"); Statement s = c.createStatement()) {
            for (String t : List.of("reg.registration", "reg.outbox", "billing.receivable", "billing.inbox", "billing.dead_letter", "billing.receivable_log")) s.execute("TRUNCATE TABLE " + t);
        }
    }

    static int count(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "root", "example_password"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next(); return r.getInt(1);
        }
    }
}
